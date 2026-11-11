package it.uniroma3.idd.wikinews;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** HTTP layer (JDK built-in server): the web page and the JSON API. */
public final class WebServer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Set<String> MODELS = Set.of("italian", "plain", "lmdirichlet");

    /** Thrown for invalid input; mapped to HTTP 422. */
    static final class BadRequest extends RuntimeException {
        BadRequest(String message) { super(message); }
    }

    private WebServer() {}

    /** Starts on localhost only; port 0 picks a free port. */
    public static HttpServer start(WikinewsSearcher searcher, int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", ex -> handle(ex, () -> {
            if (!ex.getRequestURI().getPath().equals("/")) return reply(ex, 404, "text/plain", "not found".getBytes(UTF_8));
            try (InputStream page = WebServer.class.getResourceAsStream("/static/index.html")) {
                return reply(ex, 200, "text/html; charset=utf-8", page.readAllBytes());
            }
        }));
        server.createContext("/api/search", ex -> handle(ex, () -> {
            Map<String, String> p = params(ex.getRequestURI().getRawQuery());
            String q = p.get("q");
            if (q == null) throw new BadRequest("q is required");
            if (q.length() > 500) throw new BadRequest("q is longer than 500 characters");
            int page = intParam(p, "page", 1, 1, 1000);
            int size = intParam(p, "size", 10, 1, 10);
            String model = p.getOrDefault("model", "italian");
            if (!MODELS.contains(model)) throw new BadRequest("model must be italian, plain or lmdirichlet");
            String sort = p.getOrDefault("sort", "relevance");
            if (!sort.equals("relevance") && !sort.equals("date")) throw new BadRequest("sort must be relevance or date");
            String from = dateParam(p, "date_from"), to = dateParam(p, "date_to");
            return json(ex, 200, searcher.search(q, page, size, WikinewsSearcher.Model.valueOf(model),
                    sort.equals("date"), from, to, 3f));
        }));
        server.createContext("/api/stats", ex -> handle(ex, () -> json(ex, 200, searcher.stats())));
        server.start();
        return server;
    }

    private interface Handler { int run() throws IOException; }

    private static void handle(HttpExchange ex, Handler handler) {
        try {
            if (!ex.getRequestMethod().equals("GET")) {
                reply(ex, 405, "text/plain", "method not allowed".getBytes(UTF_8));
                return;
            }
            handler.run();
        } catch (BadRequest e) {
            safeJson(ex, 422, Map.of("detail", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            safeJson(ex, 500, Map.of("detail", "internal error"));
        } finally {
            ex.close(); // not try-with-resources: that would close before the catch blocks can reply
        }
    }

    private static int json(HttpExchange ex, int status, Object body) throws IOException {
        return reply(ex, status, "application/json; charset=utf-8", JSON.writeValueAsBytes(body));
    }

    private static void safeJson(HttpExchange ex, int status, Object body) {
        try { json(ex, status, body); } catch (IOException ignored) { /* client already gone */ }
    }

    private static int reply(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
        return status;
    }

    static Map<String, String> params(String rawQuery) {
        Map<String, String> out = new HashMap<>();
        if (rawQuery == null) return out;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), UTF_8);
            out.putIfAbsent(key, value);
        }
        return out;
    }

    private static int intParam(Map<String, String> p, String name, int dflt, int min, int max) {
        String v = p.get(name);
        if (v == null) return dflt;
        try {
            int n = Integer.parseInt(v);
            if (n >= min && n <= max) return n;
        } catch (NumberFormatException ignored) { /* falls through */ }
        throw new BadRequest(name + " must be an integer between " + min + " and " + max);
    }

    private static String dateParam(Map<String, String> p, String name) {
        String v = p.get(name);
        if (v == null || v.isEmpty()) return null;
        if (!ISO_DATE.matcher(v).matches()) throw new BadRequest(name + " must be YYYY-MM-DD");
        return v;
    }
}
