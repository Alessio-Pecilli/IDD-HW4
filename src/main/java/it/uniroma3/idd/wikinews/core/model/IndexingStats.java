package it.uniroma3.idd.wikinews.core.model;

/**
 * Record che incapsula le statistiche dell'operazione di indicizzazione.
 */
public record IndexingStats(
        int read,
        int empty,
        int indexed,
        int olderRevisions,
        int documents,
        double seconds
) {}
