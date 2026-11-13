package it.uniroma3.idd.wikinews;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Fixture per i test di integrazione ed end-to-end.
 * Pattern applicati:
 * - Pure Fabrication (GRASP): classe di supporto per generare dinamicamente in memoria un archivio compresso
 *   sintetico conforme allo schema del dump Wikimedia Enterprise.
 */
final class Fixtures {

    private Fixtures() {}

    /**
     * Costruisce un record sintetico con corpo HTML Parsoid e metadati conformi allo schema Wikinews Enterprise.
     */
    static Map<String, Object> record(int id, long version, String title, String date, List<String> paragraphs,
                                      List<String> categories) {
        StringBuilder body = new StringBuilder();
        for (String p : paragraphs) body.append("<p>").append(p).append("</p>");
        String html = paragraphs.isEmpty() ? ""
                : "<html><head><style>.x{}</style></head><body><section><p><b>" + date + "</b></p>" + body
                  + "</section></body></html>";
        List<Map<String, String>> cats = new ArrayList<>();
        for (String c : categories) cats.add(Map.of("name", "Categoria:" + c));
        return Map.of(
                "identifier", id,
                "name", title,
                "url", "https://it.wikinews.org/wiki/" + title.replace(' ', '_'),
                "version", Map.of("identifier", version),
                "categories", cats,
                "article_body", Map.of("html", html, "wikitext", "{{Data|" + date + "}}\n..."));
    }

    /** Documenti sintetici di test per coprire casi limite, stemming, elisioni, deduplicazione revisioni. */
    static final List<Map<String, Object>> DOCS = List.of(
            record(1, 10, "Terremoto in Abruzzo", "6 aprile 2009",
                    List.of("Un forte terremoto ha colpito L'Aquila nella notte.", "Le scosse sono state avvertite a Roma."),
                    List.of("Disastri e incidenti", "Italia")),
            record(2, 10, "Elezioni presidenziali negli Stati Uniti", "4 novembre 2008",
                    List.of("Barack Obama vince le elezioni e diventa presidente.", "Grande festa a Chicago."),
                    List.of("Politica", "Stati Uniti d'America")),
            record(3, 10, "La Juventus vince il campionato", "20 maggio 2012",
                    List.of("La squadra torinese conquista lo scudetto di calcio.", "Festa per i tifosi della città."),
                    List.of("Sport", "Calcio")),
            record(4, 10, "Nuove scosse di terremoti in Giappone", "11 marzo 2011",
                    List.of("Diversi terremoti hanno colpito la costa. Allarme tsunami.", "Tokyo ha sentito la scossa."),
                    List.of("Disastri e incidenti", "Asia")),
            record(5, 10, "Elezione del presidente della Repubblica", "10 maggio 2006",
                    List.of("Il parlamento ha eletto il nuovo presidente della Repubblica italiana.", "Napolitano al Quirinale."),
                    List.of("Politica", "Italia")),
            record(6, 10, "Pagina vuota", "1 gennaio 2010", List.of(), List.of()),        // nessun testo -> scartato
            record(3, 1, "La Juventus vince il campionato (vecchia)", "20 maggio 2012",   // revisione obsoleta -> ignorata
                    List.of("Bozza."), List.of("Sport")),
            record(1, 99, "Terremoto in Abruzzo", "6 aprile 2009",                       // revisione più recente -> sovrascrive
                    List.of("Un forte terremoto ha colpito L'Aquila nella notte.",
                            "Le scosse sono state avvertite a Roma e Pescara."),
                    List.of("Disastri e incidenti", "Italia")));

    /**
     * Genera al volo il file compresso .tar.gz contenente i documenti sintetici in formato ndjson.
     */
    static Path dump(Path dir) throws IOException {
        ObjectMapper json = new ObjectMapper();
        StringBuilder ndjson = new StringBuilder();
        for (Map<String, Object> d : DOCS) ndjson.append(json.writeValueAsString(d)).append('\n');
        byte[] data = ndjson.toString().getBytes(UTF_8);
        Path path = dir.resolve("test.json.tar.gz");
        try (OutputStream file = Files.newOutputStream(path);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(file))) {
            TarArchiveEntry entry = new TarArchiveEntry("itwikinews_namespace_0_0.ndjson");
            entry.setSize(data.length);
            tar.putArchiveEntry(entry);
            tar.write(data);
            tar.closeArchiveEntry();
        }
        return path;
    }
}
