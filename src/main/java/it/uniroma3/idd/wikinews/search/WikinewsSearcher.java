package it.uniroma3.idd.wikinews.search;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.queryparser.simple.SimpleQueryParser;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortedSetSortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermRangeQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.search.similarities.LMDirichletSimilarity;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.search.uhighlight.DefaultPassageFormatter;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.Version;

import it.uniroma3.idd.wikinews.core.analysis.Analyzers;
import it.uniroma3.idd.wikinews.core.model.IndexStats;
import it.uniroma3.idd.wikinews.core.model.SearchHit;
import it.uniroma3.idd.wikinews.core.model.SearchModel;
import it.uniroma3.idd.wikinews.core.model.SearchResult;

/**
 * Facade del motore di ricerca Lucene: parsing delle query, ranking (BM25 o LM Dirichlet),
 * filtri temporali, ordinamento, paginazione ed evidenziazione dei passaggi (snippet).
 *
 * Pattern applicati:
 * - Facade (GoF): offre un'interfaccia semplice e unificata verso il sottosistema Lucene
 *   (IndexSearcher, UnifiedHighlighter, DirectoryReader, SimpleQueryParser).
 * - Strategy (GoF): consente l'intercambiabilità a runtime dell'algoritmo di ranking e del modello linguistico.
 * - Controller (GRASP): coordina l'interrogazione dell'indice.
 * - Information Expert (GRASP): incapsula la conoscenza della struttura dei campi indicizzati e delle impostazioni di boosting e scoring.
 */
public class WikinewsSearcher implements SearchService {

    /** Alias retrocompatibili con le classi preesistenti per agevolare il testing. */
    public static final class Compatibility {
        public static it.uniroma3.idd.wikinews.core.model.SearchModel toModel(String name) {
            return it.uniroma3.idd.wikinews.core.model.SearchModel.fromString(name);
        }
    }

    /** Operatori sintattici abilitati nel SimpleQueryParser. */
    public static final int FLAGS = SimpleQueryParser.AND_OPERATOR | SimpleQueryParser.OR_OPERATOR
            | SimpleQueryParser.NOT_OPERATOR | SimpleQueryParser.PHRASE_OPERATOR | SimpleQueryParser.PREFIX_OPERATOR
            | SimpleQueryParser.PRECEDENCE_OPERATORS | SimpleQueryParser.WHITESPACE_OPERATOR;

    private static final Sort BY_DATE;
    static {
        SortedSetSortField date = new SortedSetSortField("date", true);
        date.setMissingValue(SortField.STRING_FIRST);
        BY_DATE = new Sort(date, SortField.FIELD_SCORE);
    }

    private final DirectoryReader reader;
    private final IndexSearcher bm25;
    private final IndexSearcher lm;
    private final Analyzer analyzer = Analyzers.perField();
    private final UnifiedHighlighter highlighter;

    public WikinewsSearcher(Path indexDir) throws IOException {
        this.reader = DirectoryReader.open(FSDirectory.open(indexDir));
        this.bm25 = new IndexSearcher(reader);
        this.lm = new IndexSearcher(reader);
        this.lm.setSimilarity(new LMDirichletSimilarity(2000f));
        this.highlighter = UnifiedHighlighter.builder(bm25, analyzer)
                .withFormatter(new DefaultPassageFormatter("<mark>", "</mark>", " … ", true))
                .build();
    }

    @Override
    public Query buildQuery(String text, SearchModel model, float titleBoost, String dateFrom, String dateTo) {
        String suffix = model == SearchModel.PLAIN ? "_plain" : "";
        SimpleQueryParser parser = new SimpleQueryParser(analyzer,
                Map.of("title" + suffix, titleBoost, "body" + suffix, 1f), FLAGS);
        parser.setDefaultOperator(Occur.MUST);
        Query query = parser.parse(text);
        if (dateFrom == null && dateTo == null) return query;
        return new BooleanQuery.Builder()
                .add(query, Occur.MUST)
                .add(TermRangeQuery.newStringRange("date", dateFrom, dateTo, true, true), Occur.FILTER)
                .build();
    }

    @Override
    public SearchResult search(String text, int page, int size, SearchModel model, boolean sortByDate,
                               String dateFrom, String dateTo, float titleBoost) throws IOException {
        long t0 = System.nanoTime();
        if (text == null || text.isBlank()) return new SearchResult(text, 0, List.of(), 0);
        Query query = buildQuery(text, model, titleBoost, dateFrom, dateTo);
        IndexSearcher searcher = model == SearchModel.LMDIRICHLET ? lm : bm25;
        int n = page * size;
        TopDocs top = sortByDate ? searcher.search(query, n, BY_DATE, true) : searcher.search(query, n);
        ScoreDoc[] hits = Arrays.copyOfRange(top.scoreDocs, Math.min((page - 1) * size, top.scoreDocs.length),
                top.scoreDocs.length);
        String[] snippets = snippets(text, hits);
        StoredFields stored = searcher.storedFields();
        List<SearchHit> results = new ArrayList<>();
        for (int i = 0; i < hits.length; i++) {
            Document d = stored.document(hits[i].doc, Set.of("id", "title", "url", "date", "categories"));
            float score = hits[i].score;
            List<String> categories = Arrays.asList(d.getValues("categories"));
            results.add(new SearchHit(d.get("id"), d.get("title"), d.get("url"), d.get("date"), categories,
                    Float.isNaN(score) ? null : Math.round(score * 1000) / 1000f, snippets[i]));
        }
        return new SearchResult(text, searcher.count(query), results, Math.round((System.nanoTime() - t0) / 1e5) / 10.0);
    }

    @Override
    public List<Document> topDocuments(String text, SearchModel model, float titleBoost, int k) throws IOException {
        return topDocuments(text, model, titleBoost, k, null);
    }

    @Override
    public List<Document> topDocuments(String text, SearchModel model, float titleBoost, int k, Similarity similarity) throws IOException {
        IndexSearcher searcher;
        if (similarity != null) {
            searcher = new IndexSearcher(reader);
            searcher.setSimilarity(similarity);
        } else {
            searcher = model == SearchModel.LMDIRICHLET ? lm : bm25;
        }
        StoredFields stored = searcher.storedFields();
        List<Document> docs = new ArrayList<>();
        for (ScoreDoc sd : searcher.search(buildQuery(text, model, titleBoost, null, null), k).scoreDocs) {
            docs.add(stored.document(sd.doc, Set.of("id", "categories")));
        }
        return docs;
    }

    @Override
    public int countCategory(String category) throws IOException {
        return bm25.count(new TermQuery(new Term("categories", category)));
    }

    @Override
    public DirectoryReader reader() {
        return reader;
    }

    @Override
    public IndexStats stats() throws IOException {
        Terms dates = MultiTerms.getTerms(reader, "date");
        BytesRef first = dates == null ? null : dates.getMin();
        BytesRef last = dates == null ? null : dates.getMax();
        return new IndexStats(reader.numDocs(), first == null ? null : first.utf8ToString(),
                last == null ? null : last.utf8ToString(), Version.LATEST.toString());
    }

    private String[] snippets(String text, ScoreDoc[] hits) throws IOException {
        if (hits.length == 0) return new String[0];
        Query q = new SimpleQueryParser(analyzer, Map.of("body", 1f), FLAGS).parse(text);
        TopDocs page = new TopDocs(new TotalHits(hits.length, TotalHits.Relation.EQUAL_TO), hits);
        String[] out = highlighter.highlight("body", q, page, 2);
        for (int i = 0; i < out.length; i++) if (out[i] == null) out[i] = "";
        return out;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
