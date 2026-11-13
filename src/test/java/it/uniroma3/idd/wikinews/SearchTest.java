package it.uniroma3.idd.wikinews;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import it.uniroma3.idd.wikinews.WikinewsSearcher.Model;
import it.uniroma3.idd.wikinews.WikinewsSearcher.Result;

/**
 * Test di integrazione del motore di ricerca.
 * Indicizza il dump sintetico compresso generato al volo in un indice Lucene temporaneo
 * e valida i casi d'uso di Information Retrieval:
 * pipeline linguistica, stemming, accenti, elisioni, filtri temporali, paginazione ed evidenziazione.
 */
class SearchTest {

    @TempDir
    static Path tmp;
    static WikinewsIndexer.Stats stats;
    static WikinewsSearcher searcher;

    @BeforeAll
    static void index() throws IOException {
        stats = WikinewsIndexer.build(Fixtures.dump(tmp), tmp.resolve("index"), null);
        searcher = new WikinewsSearcher(tmp.resolve("index"));
    }

    @AfterAll
    static void close() throws IOException {
        searcher.close();
    }

    static Result search(String q) throws IOException {
        return searcher.search(q, 1, 10, Model.italian, false, null, null, 3f);
    }

    static List<String> ids(Result r) {
        return r.results().stream().map(WikinewsSearcher.Hit::id).toList();
    }

    static Set<String> idSet(Result r) {
        return r.results().stream().map(WikinewsSearcher.Hit::id).collect(Collectors.toSet());
    }

    static List<String> tokens(Analyzer a, String text) throws IOException {
        List<String> out = new ArrayList<>();
        try (TokenStream ts = a.tokenStream("body", text)) {
            CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) out.add(term.toString());
            ts.end();
        }
        return out;
    }

    @Test
    void italianAnalyzerPipeline() throws IOException {
        String s = "Il terremoto dell'Aquila ha colpito la città";
        assertEquals(List.of("terremot", "aquil", "colpit", "citta"), tokens(Analyzers.italian(), s));
        assertEquals(List.of("il", "terremoto", "dell'aquila", "ha", "colpito", "la", "citta"), tokens(Analyzers.plain(), s));
    }

    @Test
    void indexingStats() {
        assertEquals(8, stats.read());
        assertEquals(1, stats.empty());           // page with no text skipped
        assertEquals(1, stats.olderRevisions());  // stale revision ignored
        assertEquals(5, stats.documents());
    }

    @Test
    void keepsNewestRevision() throws IOException {
        assertEquals(List.of("1"), ids(search("pescara")));  // newer revision of doc 1 replaced the old one
        assertEquals(0, search("bozza").total());            // older revision of doc 3 ignored
        assertEquals("La Juventus vince il campionato", search("juventus").results().get(0).title());
    }

    @Test
    void stemmingFindsInflectedForms() throws IOException {
        assertEquals(Set.of("1", "4"), idSet(search("terremoto")));   // doc 4 only says "terremoti"
        assertEquals(Set.of("1"), idSet(searcher.search("terremoto", 1, 10, Model.plain, false, null, null, 3f)));
        assertEquals(Set.of("2", "5"), idSet(search("elezioni")));
    }

    @Test
    void accentsAndElision() throws IOException {
        assertEquals(List.of("3"), ids(search("citta")));
        assertEquals(List.of("1"), ids(search("aquila")));   // "L'Aquila": elision filter strips the article
    }

    @Test
    void phraseAndBooleanOperators() throws IOException {
        assertEquals(List.of("5"), ids(search("\"presidente della repubblica\"")));
        assertEquals(List.of("2"), ids(search("presidente obama")));          // words are ANDed
        assertEquals(Set.of("2", "3"), idSet(search("juventus | obama")));
        assertEquals(List.of("1"), ids(search("terremoto -giappone")));
        assertEquals(Set.of("1", "4"), idSet(search("terrem*")));
    }

    @Test
    void titleBoost() throws IOException {
        // both docs mention "presidente" in the body; only doc 5 has it in the title
        assertEquals("5", ids(search("presidente")).get(0));
    }

    @Test
    void lmDirichletModel() throws IOException {
        assertEquals(Set.of("1", "4"), idSet(searcher.search("terremoto", 1, 10, Model.lmdirichlet, false, null, null, 3f)));
    }

    @Test
    void dateFilterAndSort() throws IOException {
        assertEquals(List.of("4"), ids(searcher.search("terremoto", 1, 10, Model.italian, false, "2010-01-01", null, 3f)));
        assertEquals(List.of("4", "1", "2", "5"),
                ids(searcher.search("presidente | terremoto", 1, 10, Model.italian, true, null, null, 3f)));
    }

    @Test
    void pagination() throws IOException {
        Set<String> seen = new java.util.HashSet<>();
        for (int page = 1; page <= 3; page++) {
            Result r = searcher.search("presidente | terremoto | juventus", page, 2, Model.italian, false, null, null, 3f);
            assertEquals(5, r.total());
            seen.addAll(ids(r));
        }
        assertEquals(5, seen.size());
    }

    @Test
    void noResultsAndEmptyQuery() throws IOException {
        assertEquals(0, search("parolainesistente").total());
        assertEquals(0, search("   ").total());
        // a purely negative query means "everything except": SimpleQueryParser adds match-all
        assertEquals(Set.of("2", "3", "5"), idSet(search("-terremoto")));
    }

    @Test
    void resultFieldsAndHighlight() throws IOException {
        WikinewsSearcher.Hit hit = search("scudetto").results().get(0);
        assertTrue(hit.url().startsWith("https://it.wikinews.org/wiki/"));
        assertEquals("2012-05-20", hit.date());
        assertTrue(hit.score() > 0);
        assertTrue(hit.snippet().contains("<mark>scudetto</mark>"), hit.snippet());
    }

    @Test
    void indexStats() throws IOException {
        WikinewsSearcher.Stats s = searcher.stats();
        assertEquals(5, s.documents());
        assertEquals("2006-05-10", s.first_date());
        assertEquals("2012-05-20", s.last_date());
    }
}
