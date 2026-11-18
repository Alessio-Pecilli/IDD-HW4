package it.uniroma3.idd.wikinews;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

import it.uniroma3.idd.wikinews.indexing.WikinewsIndexer;
import it.uniroma3.idd.wikinews.search.SearchService;
import it.uniroma3.idd.wikinews.search.WikinewsSearcher;
import it.uniroma3.idd.wikinews.web.WebServer;

/**
 * Test end-to-end sul server HTTP reale (localhost).
 * Valida il rendering della SPA, le API REST JSON, le risposte HTTP 422 a input malformati
 * e la protezione da attacchi di SQL/XSS/Query injection.
 */
class WebServerTest {

    @TempDir
    static Path tmp;
    static SearchService searcher;
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
        assertTrue(r.body().contains("<link rel=\"stylesheet\" href=\"/style.css\">"));
        assertEquals(404, get("/nope").statusCode());
    }

    @Test
    void styleCss() throws Exception {
        HttpResponse<String> r = get("/style.css");
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").contains("text/css"));
        assertTrue(r.body().contains("--primary: #1E4D7B"));
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
    void validationErrorsReturn422() throws Exception {
        assertEquals(422, get("/api/search").statusCode());                          // missing q
        assertEquals(422, get("/api/search?q=" + "x".repeat(501)).statusCode());     // q too long
        assertEquals(422, get("/api/search?q=x&page=0").statusCode());               // page < 1
        assertEquals(422, get("/api/search?q=x&page=abc").statusCode());             // page not integer
        assertEquals(422, get("/api/search?q=x&size=20").statusCode());              // size > 10
        assertEquals(422, get("/api/search?q=x&model=invalid").statusCode());        // invalid model
        assertEquals(422, get("/api/search?q=x&sort=unknown").statusCode());         // invalid sort
        assertEquals(422, get("/api/search?q=x&date_from=01-01-2020").statusCode()); // invalid date format
    }

    @Test
    void methodNotAllowedOnPost() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + server.getAddress().getPort() + "/api/search"))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(405, HTTP.send(req, HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    @Test
    void injectionAttacksDoNotCrash() throws Exception {
        // Query injection (sintassi Lucene malformata): SimpleQueryParser deve degradare graziosamente senza 500
        assertEquals(200, get("/api/search?q=" + q("((( AND OR NOT \"\"")).statusCode());
        // XSS attempt in query: non deve causare errori né rompere il JSON
        HttpResponse<String> r = get("/api/search?q=" + q("<script>alert(1)</script>"));
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").contains("application/json"));
    }
}
