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

/** Streams the Wikimedia Enterprise dump (tar.gz of ndjson) into a Lucene index. */
public final class WikinewsIndexer {

    /** Body: stored (for snippets) and indexed with offsets, so highlighting needs no re-analysis. */
    static final FieldType BODY_TYPE = new FieldType(TextField.TYPE_STORED);
    static {
        BODY_TYPE.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
        BODY_TYPE.freeze();
    }

    public record Stats(int read, int empty, int indexed, int olderRevisions, int documents, double seconds) {}

    private WikinewsIndexer() {}

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
                BufferedReader lines = new BufferedReader(new InputStreamReader(tar, UTF_8)); // not closed: owns the tar
                for (String line; (line = lines.readLine()) != null; ) {
                    if (line.isBlank()) continue;
                    JsonNode rec = json.readTree(line);
                    read++;
                    Document doc = toDocument(rec);
                    if (doc == null) {
                        empty++;
                    } else {
                        // the dump holds several revisions of some pages: keep only the newest
                        String id = doc.get("id");
                        long version = rec.path("version").path("identifier").asLong(1);
                        Long seen = versions.get(id);
                        if (seen != null && seen >= version) {
                            older++;
                        } else {
                            versions.put(id, version);
                            writer.updateDocument(new Term("id", id), doc); // Lucene: delete + add
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

    /** Dump record -> Lucene document, or null when it has no usable text. */
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
        if (date != null) doc.add(new KeywordField("date", date, Field.Store.YES)); // ISO dates: string order = time order
        for (String c : categories) doc.add(new KeywordField("categories", c, Field.Store.YES));
        return doc;
    }
}
