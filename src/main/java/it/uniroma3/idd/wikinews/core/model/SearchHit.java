package it.uniroma3.idd.wikinews.core.model;

import java.util.List;

/**
 * Singolo risultato di ricerca (Hit) arricchito con categorie, snippet e metadati.
 */
public record SearchHit(
        String id,
        String title,
        String url,
        String date,
        List<String> categories,
        Float score,
        String snippet
) {}
