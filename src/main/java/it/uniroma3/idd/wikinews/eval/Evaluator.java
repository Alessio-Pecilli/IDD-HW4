package it.uniroma3.idd.wikinews.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.MultiBits;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.util.Bits;

import it.uniroma3.idd.wikinews.core.model.SearchModel;
import it.uniroma3.idd.wikinews.search.SearchService;

/**
 * Valutazione sperimentale di efficacia ed efficienza sull'indice reale con collezioni di test automatiche:
 *  1. Known-item search: N articoli casuali campionati devono essere ritrovati tramite
 *     (a) il loro titolo integrale, (b) 4 parole casuali del loro corpo di testo.
 *     Metriche: Success@1, Success@10, MRR@10.
 *  2. Query tematiche (Topical queries): un documento è considerato rilevante se possiede la categoria
 *     Wikinews corrispondente assegnata dai redattori.
 *     Metriche: Precision@10, nDCG@10.
 *
 * Pattern applicati:
 * - Pure Fabrication (GRASP): incapsula l'intero protocollo di misurazione IR,
 *   il calcolo delle metriche matematiche e la generazione del report markdown.
 * - Strategy (GoF): valuta e confronta le differenti configurazioni di ranking (Config).
 */
public final class Evaluator {

    /** Query tematiche con la relativa categoria Wikinews usata come ground truth. */
    public static final String[][] TOPICAL = {
            {"terremoto", "Terremoti"},
            {"incidente aereo", "Incidenti aerei"},
            {"elezioni politiche 2006", "Elezioni politiche italiane 2006"},
            {"gran premio formula 1", "Formula 1"},
            {"tappa giro d'italia", "Giro d'Italia"},
            {"olimpiadi pechino", "XXIX Olimpiade estiva"},
            {"papa benedetto", "Benedetto XVI"},
            {"contagi coronavirus", "COVID-19"},
            {"guerra ucraina", "Ucraina"},
            {"missione spaziale", "Astronautica"},
            {"torneo di tennis", "Tennis"},
            {"incendio", "Incendi"},
            {"attentato bomba", "Attentati"},
            {"mondiali di calcio 2010", "Campionato mondiale di calcio 2010"},
            {"omicidio", "Omicidi"},
            {"berlusconi", "Silvio Berlusconi"},
            {"mostra", "Mostre"},
            {"film", "Cinema"},
            {"pallanuoto", "Pallanuoto"},
            {"estrazione superenalotto", "Superenalotto"},
    };

    /** Configurazione sperimentale da valutare comparativamente. */
    public record Config(String name, SearchModel model, float titleBoost, Similarity similarity) {
        public Config(String name, SearchModel model, float titleBoost) {
            this(name, model, titleBoost, null);
        }
    }

    /** Insieme delle configurazioni per l'ablation study e per il tuning dei parametri BM25 (k1 e b). */
    public static final List<Config> CONFIGS = List.of(
            new Config("BM25 (k1=1.2, b=0.75) + analyzer italiano, title^3", SearchModel.ITALIAN, 3f),
            new Config("BM25 (k1=1.6, b=0.40 news-tuned) + analyzer italiano, title^3", SearchModel.ITALIAN, 3f, new BM25Similarity(1.6f, 0.40f)),
            new Config("BM25 (k1=1.2, b=0.75) + analyzer italiano, no boost", SearchModel.ITALIAN, 1f),
            new Config("BM25 standard (no stemming), title^3", SearchModel.PLAIN, 3f),
            new Config("LM Dirichlet (mu=2000) + analyzer italiano, title^3", SearchModel.LMDIRICHLET, 3f));

    /** Campione per il known-item search (documento target, query titolo, query parole). */
    public record Sample(String id, String title, String words) {}

    private static final Pattern LONG_WORD = Pattern.compile("\\p{L}{5,}");

    private Evaluator() {}

    /**
     * Esegue l'intera campagna sperimentale, campiona i documenti con seed deterministico,
     * calcola metriche e latenze (previa fase di warm-up) e scrive la tabella markdown su file.
     */
    public static void run(SearchService searcher, int n, long seed, Path out) throws IOException {
        List<Sample> samples = sample(searcher, n, seed);
        StringBuilder md = new StringBuilder();
        var st = searcher.stats();
        md.append("# Risultati sperimentali\n\n")
          .append(String.format("Indice: %d documenti · Apache Lucene %s · known-item: %d articoli campionati (seed %d) · "
                  + "topical: %d query giudicate tramite categorie Wikinews.%n%n",
                  st.documents(), st.luceneVersion(), samples.size(), seed, TOPICAL.length))
          .append("| Configurazione | Titolo S@1 | Titolo MRR@10 | Parole S@1 | Parole S@10 | Parole MRR@10 "
                  + "| Topical P@10 | Topical nDCG@10 | Latenza media (ms) | Latenza p95 (ms) |\n")
          .append("|---|---|---|---|---|---|---|---|---|---|\n");

        for (Config c : CONFIGS) {
            // Warm-up JIT
            for (int i = 0; i < Math.min(20, samples.size()); i++) {
                searcher.topDocuments(samples.get(i).words(), c.model(), c.titleBoost(), 10, c.similarity());
            }

            int titleHitsAt1 = 0;
            double titleReciprocalRankSum = 0;
            int wordsHitsAt1 = 0, wordsHitsAt10 = 0;
            double wordsReciprocalRankSum = 0;
            List<Long> latencies = new ArrayList<>();

            for (Sample s : samples) {
                long t0 = System.nanoTime();
                List<Document> docs = searcher.topDocuments(s.title(), c.model(), c.titleBoost(), 10, c.similarity());
                latencies.add((System.nanoTime() - t0) / 1000);
                int rank = rankOf(docs, s.id());
                if (rank == 1) titleHitsAt1++;
                if (rank > 0) titleReciprocalRankSum += 1.0 / rank;

                t0 = System.nanoTime();
                docs = searcher.topDocuments(s.words(), c.model(), c.titleBoost(), 10, c.similarity());
                latencies.add((System.nanoTime() - t0) / 1000);
                rank = rankOf(docs, s.id());
                if (rank == 1) wordsHitsAt1++;
                if (rank > 0) wordsHitsAt10++;
                if (rank > 0) wordsReciprocalRankSum += 1.0 / rank;
            }

            double p10Sum = 0, ndcgSum = 0;
            for (String[] pair : TOPICAL) {
                String query = pair[0], cat = pair[1];
                long t0 = System.nanoTime();
                List<Document> docs = searcher.topDocuments(query, c.model(), c.titleBoost(), 10, c.similarity());
                latencies.add((System.nanoTime() - t0) / 1000);
                int relevant = 0;
                double dcg = 0;
                for (int i = 0; i < docs.size(); i++) {
                    boolean rel = List.of(docs.get(i).getValues("categories")).contains(cat);
                    if (rel) {
                        relevant++;
                        dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
                    }
                }
                p10Sum += relevant / 10.0;
                int totalInCat = searcher.countCategory(cat);
                double idcg = 0;
                for (int i = 0; i < Math.min(10, totalInCat); i++) {
                    idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
                }
                ndcgSum += idcg == 0 ? 0 : dcg / idcg;
            }

            Collections.sort(latencies);
            double meanLat = latencies.stream().mapToLong(Long::longValue).average().orElse(0) / 1000.0;
            double p95Lat = latencies.get((int) (latencies.size() * 0.95)) / 1000.0;
            int total = samples.size();

            md.append(String.format("| %s | %.1f%% | %.3f | %.1f%% | %.1f%% | %.3f | %.3f | %.3f | %.2f | %.2f |%n",
                    c.name(),
                    100.0 * titleHitsAt1 / total, titleReciprocalRankSum / total,
                    100.0 * wordsHitsAt1 / total, 100.0 * wordsHitsAt10 / total, wordsReciprocalRankSum / total,
                    p10Sum / TOPICAL.length, ndcgSum / TOPICAL.length,
                    meanLat, p95Lat));
            System.err.printf("Valutata configurazione: %s%n", c.name());
        }

        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString());
        System.err.println("Report salvato su " + out);
    }

    private static int rankOf(List<Document> docs, String targetId) {
        for (int i = 0; i < docs.size(); i++) {
            if (targetId.equals(docs.get(i).get("id"))) return i + 1;
        }
        return 0;
    }

    public static List<Sample> sample(SearchService searcher, int n, long seed) throws IOException {
        var reader = searcher.reader();
        StoredFields stored = reader.storedFields();
        Bits live = MultiBits.getLiveDocs(reader);
        List<Integer> allDocIds = new ArrayList<>();
        for (int i = 0; i < reader.maxDoc(); i++) {
            if (live == null || live.get(i)) allDocIds.add(i);
        }
        Collections.shuffle(allDocIds, new Random(seed));
        List<Sample> out = new ArrayList<>();
        for (int docId : allDocIds) {
            Document d = stored.document(docId, Set.of("id", "title", "body"));
            String body = d.get("body");
            if (body == null) continue;
            List<String> words = new ArrayList<>();
            Matcher m = LONG_WORD.matcher(body);
            while (m.find()) words.add(m.group());
            if (words.size() < 10) continue;
            Collections.shuffle(words, new Random(seed + docId));
            String sampleWords = String.join(" ", words.subList(0, 4));
            out.add(new Sample(d.get("id"), d.get("title"), sampleWords));
            if (out.size() >= n) break;
        }
        return out;
    }
}
