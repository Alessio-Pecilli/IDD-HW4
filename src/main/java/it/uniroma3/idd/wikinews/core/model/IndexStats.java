package it.uniroma3.idd.wikinews.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Statistiche descrittive dell'indice Lucene.
 */
public record IndexStats(
        int documents,
        @JsonProperty("first_date") String firstDate,
        @JsonProperty("last_date") String lastDate,
        @JsonProperty("lucene_version") String luceneVersion
) {}
