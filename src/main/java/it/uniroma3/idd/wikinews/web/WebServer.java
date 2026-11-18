package it.uniroma3.idd.wikinews.web;

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

import it.uniroma3.idd.wikinews.core.model.SearchModel;
import it.uniroma3.idd.wikinews.search.SearchService;

/**
 * Livello di presentazione e interfaccia di rete (HTTP Server integrato nel JDK).
 * Espone la pagina web interattiva (SPA) e le API REST in formato JSON.
 *
 * Pattern applicati:
 * - Controller (GRASP): riceve le richieste HTTP, valida i parametri in ingresso e delega
 *   l'elaborazione dell'interrogazione al motore di ricerca (SearchService).
 * - Model-View Separation: disaccoppia la logica di presentazione
 *   dalle strutture dati interne del motore Lucene.
 * - Pure Fabrication (GRASP): adatta il server HTTP incorporato per gestire routing, serializzazione JSON ed errori.
 */
public final class WebServer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Set<String> MODELS = Set.of("italian", "plain", "lmdirichlet");

    /** Eccezione per parametri o input utente non validi; mappata sul codice HTTP 422. */
    public static final class BadRequest extends RuntimeException {
        public BadRequest(String message) { super(message); }
    }

    private WebServer() {}

    /**
     * Avvia il server HTTP associato a localhost (porta 0 alloca una porta libera).
     * Utilizza i Virtual Thread di Java 21 per supportare concorrenza elevata con basso overhead.
     */
    public static HttpServer start(SearchService searcher, int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", ex -> handle(ex, () -> {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/")) {
                try (InputStream page = WebServer.class.getResourceAsStream("/static/index.html")) {
                    return reply(ex, 200, "text/html; charset=utf-8", page.readAllBytes());
                }
            } else if (path.equals("/style.css")) {
                try (InputStream css = WebServer.class.getResourceAsStream("/static/style.css")) {
                    return reply(ex, 200, "text/css; charset=utf-8", css.readAllBytes());
                }
            }
            return reply(ex, 404, "text/plain", "not found".getBytes(UTF_8));
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
            return json(ex, 200, searcher.search(q, page, size, SearchModel.fromString(model),
                    sort.equals("date"), from, to, 3f));
        }));
        server.createContext("/api/stats", ex -> handle(ex, () -> json(ex, 200, searcher.stats())));
        server.start();
        return server;
    }

    private interface Handler { int run() throws IOException; }

    /**
     * Dispatcher delle richieste HTTP:
     * valida il metodo HTTP (ammesso solo GET), gestisce gli errori di validazione (422) e le eccezioni interne (500).
     */
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
            safeJson(ex, 500, Map.of("detail", "internal server error"));
        } finally {
            ex.close();
        }
    }

    private static int reply(HttpExchange ex, int code, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, body.length);
        ex.getResponseBody().write(body);
        return code;
    }

    private static int json(HttpExchange ex, int code, Object data) throws IOException {
        return reply(ex, code, "application/json; charset=utf-8", JSON.writeValueAsBytes(data));
    }

    private static void safeJson(HttpExchange ex, int code, Object data) {
        try { json(ex, code, data); } catch (Exception ignored) {}
    }

    private static Map<String, String> params(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isBlank()) return map;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                map.put(URLDecoder.decode(pair.substring(0, eq), UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), UTF_8));
            }
        }
        return map;
    }

    private static int intParam(Map<String, String> p, String name, int def, int min, int max) {
        String val = p.get(name);
        if (val == null) return def;
        try {
            int v = Integer.parseInt(val);
            if (v < min || v > max) throw new BadRequest(name + " out of range [" + min + ", " + max + "]");
            return v;
        } catch (NumberFormatException e) {
            throw new BadRequest(name + " must be an integer");
        }
    }

    private static String dateParam(Map<String, String> p, String name) {
        String v = p.get(name);
        if (v == null || v.isBlank()) return null;
        if (!ISO_DATE.matcher(v).matches()) throw new BadRequest(name + " must be in YYYY-MM-DD format");
        return v;
    }
}
