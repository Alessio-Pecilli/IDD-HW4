package it.uniroma3.idd.wikinews;

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

import it.uniroma3.idd.wikinews.WikinewsSearcher.Model;

/**
 * Valutazione sperimentale di efficacia ed efficienza sull'indice reale con collezioni di test automatiche:
 *  1. Known-item search: N articoli casuali campionati devono essere ritrovati tramite
 *     (a) il loro titolo integrale, (b) 4 parole casuali del loro corpo di testo.
 *     Metriche: Success@1, Success@10, MRR@10.
 *  2. Query tematiche (Topical queries): un documento è considerato rilevante se possiede la categoria
 *     Wikinews corrispondente assegnata dai redattori (giudizi di rilevanza oggettivi).
 *     Metriche: Precision@10, nDCG@10 (con guadagni binari).
 *
 * Pattern applicati:
 * - Pure Fabrication (GRASP): incapsula l'intero protocollo di misurazione IR,
 *   il calcolo delle metriche matematiche e la generazione del report markdown.
 * - Strategy (GoF): valuta e confronta le differenti configurazioni di ranking (Config).
 */
public final class Evaluator {

    /** Query tematiche con la relativa categoria Wikinews usata come ground truth. */
    static final String[][] TOPICAL = {
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
    record Config(String name, Model model, float titleBoost, Similarity similarity) {
        Config(String name, Model model, float titleBoost) {
            this(name, model, titleBoost, null);
        }
    }

    /** Insieme delle configurazioni per l'ablation study e per il tuning dei parametri BM25 (k1 e b). */
    static final List<Config> CONFIGS = List.of(
            new Config("BM25 (k1=1.2, b=0.75) + analyzer italiano, title^3", Model.italian, 3f),
            new Config("BM25 (k1=1.6, b=0.40 news-tuned) + analyzer italiano, title^3", Model.italian, 3f, new BM25Similarity(1.6f, 0.40f)),
            new Config("BM25 (k1=1.2, b=0.75) + analyzer italiano, no boost", Model.italian, 1f),
            new Config("BM25 standard (no stemming), title^3", Model.plain, 3f),
            new Config("LM Dirichlet (mu=2000) + analyzer italiano, title^3", Model.lmdirichlet, 3f));

    /** Campione per il known-item search (documento target, query titolo, query parole). */
    record Sample(String id, String title, String words) {}

    private static final Pattern LONG_WORD = Pattern.compile("\\p{L}{5,}");

    private Evaluator() {}

    /**
     * Esegue l'intera campagna sperimentale, campiona i documenti con seed deterministico,
     * calcola metriche e latenze (previa fase di warm-up) e scrive la tabella markdown su file.
     */
    public static void run(WikinewsSearcher searcher, int n, long seed, Path out) throws IOException {
        List<Sample> samples = sample(searcher, n, seed);
        StringBuilder md = new StringBuilder();
        var st = searcher.stats();
        md.append("# Risultati sperimentali\n\n")
          .append(String.format("Indice: %d documenti · Apache Lucene %s · known-item: %d articoli campionati (seed %d) · "
                  + "topical: %d query giudicate tramite categorie Wikinews.%n%n",
                  st.documents(), st.lucene_version(), samples.size(), seed, TOPICAL.length))
          .append("| Configurazione | Titolo S@1 | Titolo MRR@10 | Parole S@1 | Parole S@10 | Parole MRR@10 "
                  + "| Topical P@10 | Topical nDCG@10 | Latenza media (ms) | Latenza p95 (ms) |\n")
          .append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (Config c : CONFIGS) {
            List<Double> ms = new ArrayList<>();
            double[] title = knownItem(searcher, samples, c, true, ms);
            double[] words = knownItem(searcher, samples, c, false, ms);
            double[] topical = topical(searcher, c, ms);
            Collections.sort(ms);
            double mean = ms.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double p95 = ms.get((int) (0.95 * (ms.size() - 1)));
            md.append(String.format("| %s | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f | %.1f | %.1f |%n",
                    c.name(), title[0], title[2], words[0], words[1], words[2], topical[0], topical[1], mean, p95));
            System.err.printf("%s: title %s words %s topical %s%n", c.name(), Arrays.toString(title),
                    Arrays.toString(words), Arrays.toString(topical));
        }
        md.append("\nLatenza = tempo di retrieval + ranking top-10 nel processo Java (senza snippet), dopo il warm-up.\n");
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, md);
        System.out.print(md);
    }

    /**
     * Estrae un campione casuale di N articoli idonei per il test known-item.
     */
    static List<Sample> sample(WikinewsSearcher searcher, int n, long seed) throws IOException {
        var reader = searcher.reader();
        Bits live = MultiBits.getLiveDocs(reader);
        StoredFields stored = reader.storedFields();
        Random rnd = new Random(seed);
        List<Integer> docIds = new ArrayList<>();
        for (int i = 0; i < reader.maxDoc(); i++) if (live == null || live.get(i)) docIds.add(i);
        Collections.shuffle(docIds, rnd);
        List<Sample> out = new ArrayList<>();
        for (int docId : docIds) {
            if (out.size() == n) break;
            Document d = stored.document(docId, Set.of("id", "title", "body"));
            List<String> words = new ArrayList<>();
            for (Matcher m = LONG_WORD.matcher(d.get("body")); m.find(); ) words.add(m.group());
            if (d.get("title").split("\\s+").length < 3 || words.size() < 20) continue;
            Collections.shuffle(words, rnd);
            out.add(new Sample(d.get("id"), d.get("title"), String.join(" ", words.subList(0, 4))));
        }
        return out;
    }

    /** Calcola le metriche di known-item search: restituisce {S@1, S@10, MRR@10}. */
    static double[] knownItem(WikinewsSearcher s, List<Sample> samples, Config c, boolean byTitle, List<Double> ms)
            throws IOException {
        double s1 = 0, s10 = 0, rr = 0;
        for (Sample sample : samples) {
            List<Document> top = timed(s, byTitle ? sample.title() : sample.words(), c, ms);
            for (int rank = 0; rank < top.size(); rank++) {
                if (top.get(rank).get("id").equals(sample.id())) {
                    if (rank == 0) s1++;
                    s10++;
                    rr += 1.0 / (rank + 1);
                    break;
                }
            }
        }
        int n = samples.size();
        return new double[] {s1 / n, s10 / n, rr / n};
    }

    /** Calcola le metriche tematiche con categorie: restituisce {P@10, nDCG@10}. */
    static double[] topical(WikinewsSearcher s, Config c, List<Double> ms) throws IOException {
        double p10 = 0, ndcg = 0;
        for (String[] t : TOPICAL) {
            List<Document> top = timed(s, t[0], c, ms);
            double dcg = 0, rel = 0;
            for (int i = 0; i < top.size(); i++) {
                if (Arrays.asList(top.get(i).getValues("categories")).contains(t[1])) {
                    rel++;
                    dcg += 1 / log2(i + 2);
                }
            }
            double idcg = 0;
            for (int i = 0; i < Math.min(10, s.countCategory(t[1])); i++) idcg += 1 / log2(i + 2);
            p10 += rel / 10;
            ndcg += idcg == 0 ? 0 : dcg / idcg;
        }
        return new double[] {p10 / TOPICAL.length, ndcg / TOPICAL.length};
    }

    /**
     * Esegue l'interrogazione registrando il tempo di latenza in millisecondi,
     * effettuando preventivamente una chiamata di warm-up per azzerare gli effetti di cache/JIT.
     */
    private static List<Document> timed(WikinewsSearcher s, String q, Config c, List<Double> ms) throws IOException {
        s.topDocuments(q, c.model(), c.titleBoost(), 10, c.similarity()); // warm-up: evita che la prima configurazione subisca penalità a freddo
        long t0 = System.nanoTime();
        List<Document> top = s.topDocuments(q, c.model(), c.titleBoost(), 10, c.similarity());
        ms.add((System.nanoTime() - t0) / 1e6);
        return top;
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }
}
