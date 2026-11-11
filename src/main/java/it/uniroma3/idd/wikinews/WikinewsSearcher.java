package it.uniroma3.idd.wikinews;

import java.io.Closeable;
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
import org.apache.lucene.search.uhighlight.DefaultPassageFormatter;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.Version;

/** Query parsing, ranking (BM25 or LM Dirichlet), date filter/sort, pagination and snippets. */
public final class WikinewsSearcher implements Closeable {

    public enum Model { italian, plain, lmdirichlet }

    public record Hit(String id, String title, String url, String date, Float score, String snippet) {}

    public record Result(String query, long total, List<Hit> results, double ms) {}

    public record Stats(int documents, String first_date, String last_date, String lucene_version) {}

    /** Same operators as Elasticsearch's simple_query_string; fuzzy/near/escape disabled. */
    static final int FLAGS = SimpleQueryParser.AND_OPERATOR | SimpleQueryParser.OR_OPERATOR
            | SimpleQueryParser.NOT_OPERATOR | SimpleQueryParser.PHRASE_OPERATOR | SimpleQueryParser.PREFIX_OPERATOR
            | SimpleQueryParser.PRECEDENCE_OPERATORS | SimpleQueryParser.WHITESPACE_OPERATOR;

    // reverse=true flips the missing-value order too: STRING_FIRST puts undated articles last
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
        reader = DirectoryReader.open(FSDirectory.open(indexDir));
        bm25 = new IndexSearcher(reader); // BM25Similarity is Lucene's default
        lm = new IndexSearcher(reader);
        lm.setSimilarity(new LMDirichletSimilarity(2000f)); // same norms as BM25: no reindexing needed
        highlighter = UnifiedHighlighter.builder(bm25, analyzer)
                .withFormatter(new DefaultPassageFormatter("<mark>", "</mark>", " … ", true)) // true = HTML-escape text
                .build();
    }

    /** User text -> Lucene query on title (boosted) and body, words ANDed, optional date range filter. */
    public Query buildQuery(String text, Model model, float titleBoost, String dateFrom, String dateTo) {
        String suffix = model == Model.plain ? "_plain" : "";
        SimpleQueryParser parser = new SimpleQueryParser(analyzer,
                Map.of("title" + suffix, titleBoost, "body" + suffix, 1f), FLAGS);
        parser.setDefaultOperator(Occur.MUST);
        Query query = parser.parse(text);
        if (dateFrom == null && dateTo == null) return query;
        return new BooleanQuery.Builder()
                .add(query, Occur.MUST)
                .add(TermRangeQuery.newStringRange("date", dateFrom, dateTo, true, true), Occur.FILTER) // no effect on score
                .build();
    }

    public Result search(String text, int page, int size, Model model, boolean sortByDate,
                         String dateFrom, String dateTo, float titleBoost) throws IOException {
        long t0 = System.nanoTime();
        if (text == null || text.isBlank()) return new Result(text, 0, List.of(), 0);
        Query query = buildQuery(text, model, titleBoost, dateFrom, dateTo);
        IndexSearcher searcher = model == Model.lmdirichlet ? lm : bm25;
        int n = page * size;
        TopDocs top = sortByDate ? searcher.search(query, n, BY_DATE, true) : searcher.search(query, n);
        ScoreDoc[] hits = Arrays.copyOfRange(top.scoreDocs, Math.min((page - 1) * size, top.scoreDocs.length),
                top.scoreDocs.length);
        String[] snippets = snippets(text, hits);
        StoredFields stored = searcher.storedFields();
        List<Hit> results = new ArrayList<>();
        for (int i = 0; i < hits.length; i++) {
            Document d = stored.document(hits[i].doc, Set.of("id", "title", "url", "date"));
            float score = hits[i].score;
            results.add(new Hit(d.get("id"), d.get("title"), d.get("url"), d.get("date"),
                    Float.isNaN(score) ? null : Math.round(score * 1000) / 1000f, snippets[i]));
        }
        return new Result(text, searcher.count(query), results, Math.round((System.nanoTime() - t0) / 1e5) / 10.0);
    }

    /** Top-k document ids and categories, no snippets: used by the evaluation. */
    public List<Document> topDocuments(String text, Model model, float titleBoost, int k) throws IOException {
        IndexSearcher searcher = model == Model.lmdirichlet ? lm : bm25;
        StoredFields stored = searcher.storedFields();
        List<Document> docs = new ArrayList<>();
        for (ScoreDoc sd : searcher.search(buildQuery(text, model, titleBoost, null, null), k).scoreDocs) {
            docs.add(stored.document(sd.doc, Set.of("id", "categories")));
        }
        return docs;
    }

    public int countCategory(String category) throws IOException {
        return bm25.count(new TermQuery(new Term("categories", category)));
    }

    public DirectoryReader reader() {
        return reader;
    }

    public Stats stats() throws IOException {
        Terms dates = MultiTerms.getTerms(reader, "date");
        BytesRef first = dates == null ? null : dates.getMin();
        BytesRef last = dates == null ? null : dates.getMax();
        return new Stats(reader.numDocs(), first == null ? null : first.utf8ToString(),
                last == null ? null : last.utf8ToString(), Version.LATEST.toString());
    }

    /** Snippets from the stored body; query re-parsed on "body" so every model highlights the same way. */
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
