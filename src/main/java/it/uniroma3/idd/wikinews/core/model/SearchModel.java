package it.uniroma3.idd.wikinews.core.model;

/**
 * Modelli di ranking e analisi linguistica selezionabili.
 */
public enum SearchModel {
    ITALIAN,
    PLAIN,
    LMDIRICHLET;

    public static SearchModel fromString(String value) {
        if (value == null) return ITALIAN;
        return switch (value.toLowerCase().trim()) {
            case "plain" -> PLAIN;
            case "lmdirichlet", "lm_dirichlet", "lm" -> LMDIRICHLET;
            default -> ITALIAN;
        };
    }
}
