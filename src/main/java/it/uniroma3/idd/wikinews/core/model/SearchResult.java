package it.uniroma3.idd.wikinews.core.model;

import java.util.List;

/**
 * Risultato aggregato restituito dalla ricerca (conteggio totale, hit della pagina corrente e latenza).
 */
public record SearchResult(
        String query,
        long total,
        List<SearchHit> results,
        double ms
) {}
