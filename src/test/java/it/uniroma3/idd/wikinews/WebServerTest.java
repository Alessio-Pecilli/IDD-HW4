package it.uniroma3.idd.wikinews;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/**
 * Test end-to-end sul server HTTP reale (localhost).
 * Valida il rendering della SPA, le API REST JSON, le risposte HTTP 422 a input malformati
 * e la protezione da attacchi di SQL/XSS/Query injection.
 */
class WebServerTest {

    @TempDir
    static Path tmp;
    static WikinewsSearcher searcher;
    static HttpServer server;
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void start() throws IOException {
        WikinewsIndexer.build(Fixtures.dump(tmp), tmp.resolve("index"), null);
        searcher = new WikinewsSearcher(tmp.resolve("index"));
        server = WebServer.start(searcher, 0);
    }

    @AfterAll
    static void stop() throws IOException {
        server.stop(0);
        searcher.close();
    }

    static HttpResponse<String> get(String pathAndQuery) throws Exception {
        URI uri = URI.create("http://localhost:" + server.getAddress().getPort() + pathAndQuery);
        return HTTP.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
    }

    static String q(String s) {
        return URLEncoder.encode(s, UTF_8);
    }

    @Test
    void homePage() throws Exception {
        HttpResponse<String> r = get("/");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("Wikinews IT Search"));
        assertEquals(404, get("/nope").statusCode());
    }

    @Test
    void stats() throws Exception {
        JsonNode s = JSON.readTree(get("/api/stats").body());
        assertEquals(5, s.get("documents").asInt());
        assertEquals("2006-05-10", s.get("first_date").asText());
        assertTrue(s.get("lucene_version").asText().startsWith("10."));
    }

    @Test
    void search() throws Exception {
        JsonNode r = JSON.readTree(get("/api/search?q=terremoto").body());
        assertEquals(2, r.get("total").asInt());
        assertEquals(2, r.get("results").size());
    }

    @Test
    void searchOptions() throws Exception {
        JsonNode r = JSON.readTree(get("/api/search?q=terremoto&model=plain&date_from=2009-01-01&date_to=2009-12-31&sort=date").body());
        assertEquals(1, r.get("results").size());
        assertEquals("1", r.get("results").get(0).get("id").asText());
    }

    @Test
    void inputValidation() throws Exception {
        assertEquals(422, get("/api/search").statusCode());
        assertEquals(422, get("/api/search?q=x&page=0").statusCode());
        assertEquals(422, get("/api/search?q=x&page=abc").statusCode());
        assertEquals(422, get("/api/search?q=x&sort=drop").statusCode());
        assertEquals(422, get("/api/search?q=x&model=tfidf").statusCode());
        assertEquals(422, get("/api/search?q=x&date_from=ieri").statusCode());
        assertEquals(422, get("/api/search?q=" + "x".repeat(501)).statusCode());
    }

    @Test
    void hostileQueriesNeverFail() throws Exception {
        for (String hostile : new String[] {"\"", "a AND", "NEAR(a b)", "title:x", "*", "a)(", "'; DROP TABLE docs; --",
                "<script>alert(1)</script>", "a\\", "~2", "(((", "|||", "-", "+-+"}) {
            assertEquals(200, get("/api/search?q=" + q(hostile)).statusCode(), hostile);
        }
        assertEquals(5, JSON.readTree(get("/api/stats").body()).get("documents").asInt());
    }

    @Test
    void snippetsAreHtmlEscaped() throws Exception {
        JsonNode r = JSON.readTree(get("/api/search?q=" + q("calcio")).body());
        for (JsonNode hit : r.get("results")) assertFalse(hit.get("snippet").asText().contains("<p>"));
    }
}
