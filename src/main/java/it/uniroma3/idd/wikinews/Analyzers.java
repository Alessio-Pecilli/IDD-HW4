package it.uniroma3.idd.wikinews;

import java.util.List;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.it.ItalianAnalyzer;
import org.apache.lucene.analysis.it.ItalianLightStemFilter;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.util.ElisionFilter;

/**
 * Text analysis. Each text field is indexed twice:
 *   title, body              -> Italian analyzer: elision, lowercase, asciifolding, stopwords, light stemmer
 *   title_plain, body_plain  -> plain analyzer: lowercase + asciifolding only (baseline, no stemming)
 */
public final class Analyzers {

    /** Apostrophised articles and prepositions removed by the elision filter (l'Aquila -> Aquila). */
    static final CharArraySet ELISION = new CharArraySet(List.of(
            "c", "l", "all", "dall", "dell", "nell", "sull", "coll", "pell", "gl", "agl", "dagl", "degl", "negl",
            "sugl", "un", "m", "t", "s", "v", "d", "quest", "quell", "anch", "tutt", "nessun"), true);

    private Analyzers() {}

    public static Analyzer italian() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String field) {
                Tokenizer source = new StandardTokenizer();
                TokenStream ts = new ElisionFilter(source, ELISION);
                ts = new LowerCaseFilter(ts);
                ts = new ASCIIFoldingFilter(ts);
                ts = new StopFilter(ts, ItalianAnalyzer.getDefaultStopSet());
                ts = new ItalianLightStemFilter(ts);
                return new TokenStreamComponents(source, ts);
            }

            @Override
            protected TokenStream normalize(String field, TokenStream in) { // used for prefix queries
                return new ASCIIFoldingFilter(new LowerCaseFilter(new ElisionFilter(in, ELISION)));
            }
        };
    }

    public static Analyzer plain() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String field) {
                Tokenizer source = new StandardTokenizer();
                return new TokenStreamComponents(source, new ASCIIFoldingFilter(new LowerCaseFilter(source)));
            }

            @Override
            protected TokenStream normalize(String field, TokenStream in) {
                return new ASCIIFoldingFilter(new LowerCaseFilter(in));
            }
        };
    }

    /** One analyzer for the whole index, chosen per field (PerFieldAnalyzerWrapper). */
    public static Analyzer perField() {
        Analyzer plain = plain();
        return new PerFieldAnalyzerWrapper(italian(), Map.of("title_plain", plain, "body_plain", plain));
    }
}
