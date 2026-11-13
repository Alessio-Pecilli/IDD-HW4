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
 * Pipeline di analisi del testo.
 * Pattern applicati:
 * - Factory Method (GoF): metodi di fabbrica per la creazione delle istanze degli Analyzer.
 * - Pure Fabrication (GRASP): classe di supporto coesa che incapsula la configurazione
 *   linguistica specifica per l'italiano e la baseline non flessa.
 *
 * Mappatura campi:
 *   title, body              -> Analyzer italiano: elisione, minuscole, ripiegamento ASCII,
 *                               rimozione stopword, stemmer leggero italiano.
 *   title_plain, body_plain  -> Analyzer plain (baseline): sole minuscole e ripiegamento ASCII,
 *                               senza rimozione stopword né stemming.
 */
public final class Analyzers {

    /** Articoli e preposizioni articolate elise gestite dal filtro di elisione (es. l'Aquila -> Aquila). */
    static final CharArraySet ELISION = new CharArraySet(List.of(
            "c", "l", "all", "dall", "dell", "nell", "sull", "coll", "pell", "gl", "agl", "dagl", "degl", "negl",
            "sugl", "un", "m", "t", "s", "v", "d", "quest", "quell", "anch", "tutt", "nessun"), true);

    /**
     * Stopword italiane normalizzate sia in minuscolo che con ripiegamento caratteri ASCII (ASCII folding).
     * Garantisce che stopword accentate (es. "perché", "né", "più") vengano rimosse sia in forma accentata che piana.
     */
    static final CharArraySet STOPWORDS;
    static {
        CharArraySet defaults = ItalianAnalyzer.getDefaultStopSet();
        CharArraySet folded = new CharArraySet(defaults.size() * 2, true);
        for (Object w : defaults) {
            String word = new String((char[]) w);
            folded.add(word);
            char[] output = new char[word.length() * 4];
            int len = org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter.foldToASCII(
                    word.toCharArray(), 0, output, 0, word.length());
            folded.add(new String(output, 0, len));
        }
        STOPWORDS = CharArraySet.unmodifiableSet(folded);
    }

    private Analyzers() {}

    /**
     * Fabbrica per l'analyzer dedicato alla lingua italiana.
     * Applica la catena di filtri: Tokenizer -> Elision -> LowerCase -> ASCIIFolding -> StopFilter -> ItalianLightStemmer.
     */
    public static Analyzer italian() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String field) {
                Tokenizer source = new StandardTokenizer();
                TokenStream ts = new ElisionFilter(source, ELISION);
                ts = new LowerCaseFilter(ts);
                ts = new ASCIIFoldingFilter(ts);
                ts = new StopFilter(ts, STOPWORDS);
                ts = new ItalianLightStemFilter(ts);
                return new TokenStreamComponents(source, ts);
            }

            @Override
            protected TokenStream normalize(String field, TokenStream in) { // impiegato per le query con prefisso/wildcard
                return new ASCIIFoldingFilter(new LowerCaseFilter(new ElisionFilter(in, ELISION)));
            }
        };
    }

    /**
     * Fabbrica per l'analyzer elementare "plain" (usato come baseline comparativa).
     * Non effettua rimozione di stopword né stemming per consentire il match esatto e l'ablation study.
     */
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

    /**
     * Costruisce un analizzatore composito per l'intero indice, differenziando la strategia
     * di analisi per singolo campo (PerFieldAnalyzerWrapper).
     */
    public static Analyzer perField() {
        Analyzer plain = plain();
        return new PerFieldAnalyzerWrapper(italian(), Map.of("title_plain", plain, "body_plain", plain));
    }
}
