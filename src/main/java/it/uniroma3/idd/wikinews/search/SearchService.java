package it.uniroma3.idd.wikinews.search;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.similarities.Similarity;

import it.uniroma3.idd.wikinews.core.model.IndexStats;
import it.uniroma3.idd.wikinews.core.model.SearchModel;
import it.uniroma3.idd.wikinews.core.model.SearchResult;

/**
 * Contratto astratto per il servizio di ricerca informativo su Lucene.
 * Pattern applicati:
 * - Protected Variations (GRASP): incapsula le operazioni di ricerca e ranking dietro un'interfaccia stabile,
 *   proteggendo i client (WebServer, Evaluator, CLI) dall'implementazione sottostante.
 */
public interface SearchService extends Closeable {

    /**
     * Esegue la ricerca con paginazione, calcolo dello score, estrazione degli snippet ed eventuale ordinamento per data.
     */
    SearchResult search(String text, int page, int size, SearchModel model, boolean sortByDate,
                        String dateFrom, String dateTo, float titleBoost) throws IOException;

    /**
     * Costruisce la query Lucene combinando i campi titolo (con boost) e corpo.
     */
    Query buildQuery(String text, SearchModel model, float titleBoost, String dateFrom, String dateTo);

    /**
     * Restituisce i primi k documenti (id e categorie) senza snippet.
     */
    List<Document> topDocuments(String text, SearchModel model, float titleBoost, int k) throws IOException;

    /**
     * Variante di topDocuments con supporto a Similarity customizzata.
     */
    List<Document> topDocuments(String text, SearchModel model, float titleBoost, int k, Similarity similarity) throws IOException;

    /**
     * Conta i documenti appartenenti a una specifica categoria.
     */
    int countCategory(String category) throws IOException;

    /**
     * Restituisce il DirectoryReader Lucene sottostante.
     */
    DirectoryReader reader();

    /**
     * Raccoglie le statistiche descrittive dell'indice.
     */
    IndexStats stats() throws IOException;

    @Override
    void close() throws IOException;
}
