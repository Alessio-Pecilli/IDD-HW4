package it.uniroma3.idd.wikinews;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.KeywordField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Gestore dell'indicizzazione in streaming del dump Wikimedia Enterprise (tar.gz di ndjson) su indice Lucene.
 * Pattern applicati:
 * - Creator (GRASP): WikinewsIndexer crea e assembla le istanze di org.apache.lucene.document.Document
 *   partendo dai record grezzi JSON.
 * - Controller / Facade (GRASP/GoF): coordina l'intero processo di parsing e indicizzazione verso Lucene.
 * - Pure Fabrication (GRASP): classe di servizio per la gestione della persistenza dell'indice.
 */
public final class WikinewsIndexer {

    /** Campo body: memorizzato (per gli snippet) e indicizzato con offset per l'UnifiedHighlighter. */
    static final FieldType BODY_TYPE = new FieldType(TextField.TYPE_STORED);
    static {
        BODY_TYPE.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
        BODY_TYPE.freeze();
    }

    /** Record che incapsula le statistiche dell'operazione di indicizzazione. */
    public record Stats(int read, int empty, int indexed, int olderRevisions, int documents, double seconds) {}

    private WikinewsIndexer() {}

    /**
     * Costruisce l'indice Lucene leggendo in streaming il dump compresso.
     * Mantiene solo la revisione più recente di ciascuna pagina tramite updateDocument.
     */
    public static Stats build(Path dump, Path indexDir, Integer limit) throws IOException {
        long t0 = System.nanoTime();
        int read = 0, empty = 0, indexed = 0, older = 0;
        Map<String, Long> versions = new HashMap<>();
        ObjectMapper json = new ObjectMapper();
        IndexWriterConfig config = new IndexWriterConfig(Analyzers.perField())
                .setOpenMode(IndexWriterConfig.OpenMode.CREATE)
                .setRAMBufferSizeMB(256);
        try (Directory dir = FSDirectory.open(indexDir);
             IndexWriter writer = new IndexWriter(dir, config);
             TarArchiveInputStream tar = new TarArchiveInputStream(
                     new GzipCompressorInputStream(new BufferedInputStream(Files.newInputStream(dump))))) {
            entries:
            for (TarArchiveEntry entry; (entry = tar.getNextEntry()) != null; ) {
                if (!entry.isFile() || !entry.getName().endsWith(".ndjson")) continue;
                BufferedReader lines = new BufferedReader(new InputStreamReader(tar, UTF_8)); // non chiuso direttamente: tar possiede lo stream
                for (String line; (line = lines.readLine()) != null; ) {
                    if (line.isBlank()) continue;
                    JsonNode rec = json.readTree(line);
                    read++;
                    Document doc = toDocument(rec);
                    if (doc == null) {
                        empty++;
                    } else {
                        // Il dump contiene revisioni multiple di alcune pagine: conserviamo solo la più recente
                        String id = doc.get("id");
                        long version = rec.path("version").path("identifier").asLong(1);
                        Long seen = versions.get(id);
                        if (seen != null && seen >= version) {
                            older++;
                        } else {
                            versions.put(id, version);
                            writer.updateDocument(new Term("id", id), doc); // Lucene: equivale a delete + add atomico
                            indexed++;
                            if (indexed % 1000 == 0) System.err.printf("%d documents indexed%n", indexed);
                        }
                    }
                    if (limit != null && read >= limit) break entries;
                }
            }
            writer.commit();
            Stats stats = new Stats(read, empty, indexed, older, writer.getDocStats().numDocs,
                    (System.nanoTime() - t0) / 1e9);
            System.err.println("done: " + stats);
            return stats;
        }
    }

    /**
     * Trasforma un record JSON del dump in un Document Lucene, oppure restituisce null se privo di testo utile.
     */
    static Document toDocument(JsonNode rec) {
        String body = TextExtractor.htmlToText(rec.path("article_body").path("html").asText(""));
        if (body.isEmpty()) return null;
        List<String> categories = new ArrayList<>();
        rec.path("categories").forEach(c -> categories.add(c.path("name").asText().replaceFirst("^Categoria:", "")));
        String title = rec.path("name").asText();
        String date = TextExtractor.extractDate(rec.path("article_body").path("wikitext").asText(""), categories);

        Document doc = new Document();
        doc.add(new KeywordField("id", rec.path("identifier").asText(), Field.Store.YES));
        doc.add(new TextField("title", title, Field.Store.YES));
        doc.add(new TextField("title_plain", title, Field.Store.NO));
        doc.add(new Field("body", body, BODY_TYPE));
        doc.add(new TextField("body_plain", body, Field.Store.NO));
        doc.add(new StoredField("url", rec.path("url").asText()));
        if (date != null) doc.add(new KeywordField("date", date, Field.Store.YES)); // Date ISO: ordinamento alfabetico = ordinamento cronologico
        for (String c : categories) doc.add(new KeywordField("categories", c, Field.Store.YES));
        return doc;
    }
}
