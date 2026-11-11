package it.uniroma3.idd.wikinews;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Command line entry point: index, serve, eval. */
public final class Main {

    private static final String USAGE = """
            usage:
              java -jar target/wikinews-search.jar index <dump.tar.gz> [indexDir] [--limit N]
              java -jar target/wikinews-search.jar serve [indexDir] [--port 8000]
              java -jar target/wikinews-search.jar eval  [indexDir] [--n 300] [--out eval/results.md]
            indexDir defaults to data/index""";

    public static void main(String[] args) throws Exception {
        if (args.length == 0) exit();
        List<String> positional = new ArrayList<>();
        String limit = null, port = "8000", n = "300", out = "eval/results.md";
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--limit" -> limit = value(args, ++i);
                case "--port" -> port = value(args, ++i);
                case "--n" -> n = value(args, ++i);
                case "--out" -> out = value(args, ++i);
                default -> positional.add(args[i]);
            }
        }
        switch (args[0]) {
            case "index" -> {
                if (positional.isEmpty()) exit();
                WikinewsIndexer.build(Path.of(positional.get(0)), indexDir(positional, 1),
                        limit == null ? null : Integer.parseInt(limit));
            }
            case "serve" -> {
                WikinewsSearcher searcher = new WikinewsSearcher(indexDir(positional, 0));
                var server = WebServer.start(searcher, Integer.parseInt(port));
                System.err.println("Wikinews IT Search on http://localhost:" + server.getAddress().getPort());
            }
            case "eval" -> {
                try (WikinewsSearcher searcher = new WikinewsSearcher(indexDir(positional, 0))) {
                    Evaluator.run(searcher, Integer.parseInt(n), 42, Path.of(out));
                }
            }
            default -> exit();
        }
    }

    private static Path indexDir(List<String> positional, int i) {
        return Path.of(positional.size() > i ? positional.get(i) : "data/index");
    }

    private static String value(String[] args, int i) {
        if (i >= args.length) exit();
        return args[i];
    }

    private static void exit() {
        System.err.println(USAGE);
        System.exit(2);
    }
}
