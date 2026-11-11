package it.uniroma3.idd.wikinews;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

/** Document preparation: visible text of the Parsoid HTML and the publication date. */
public final class TextExtractor {

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("gennaio", 1), Map.entry("febbraio", 2), Map.entry("marzo", 3), Map.entry("aprile", 4),
            Map.entry("maggio", 5), Map.entry("giugno", 6), Map.entry("luglio", 7), Map.entry("agosto", 8),
            Map.entry("settembre", 9), Map.entry("ottobre", 10), Map.entry("novembre", 11), Map.entry("dicembre", 12));
    private static final Pattern DATE = Pattern.compile("(\\d{1,2})\\s*[º°]?\\s+([a-zA-Z]+)\\s+(\\d{4})");
    private static final Pattern DATA_TEMPLATE = Pattern.compile("\\{\\{\\s*[Dd]ata\\s*\\|\\s*([^|}]+)");
    private static final String BLOCKS = "p,br,li,td,th,tr,div,h1,h2,h3,h4,h5,h6,section,dd,dt";

    private TextExtractor() {}

    /** Visible text of a page, one line per block element. */
    public static String htmlToText(String html) {
        if (html == null || html.isEmpty()) return "";
        Document doc = Jsoup.parse(html);
        doc.select("style,script,link,meta,head").remove();
        for (Element el : doc.body().select(BLOCKS)) el.after(new TextNode("\n"));
        return doc.body().wholeText().lines()
                .map(line -> line.strip().replaceAll("\\s+", " "))
                .filter(line -> !line.isEmpty())
                .collect(Collectors.joining("\n"));
    }

    /** "14 gennaio 2007" -> "2007-01-14", or null. */
    public static String parseItalianDate(String s) {
        if (s == null) return null;
        Matcher m = DATE.matcher(s);
        if (!m.find()) return null;
        Integer month = MONTHS.get(m.group(2).toLowerCase());
        int day = Integer.parseInt(m.group(1));
        if (month == null || day < 1 || day > 31) return null;
        return String.format("%04d-%02d-%02d", Integer.parseInt(m.group(3)), month, day);
    }

    /** Publication date from the {{Data|...}} template, falling back to the date category. */
    public static String extractDate(String wikitext, List<String> categories) {
        Matcher m = DATA_TEMPLATE.matcher(wikitext == null ? "" : wikitext);
        if (m.find()) {
            String d = parseItalianDate(m.group(1));
            if (d != null) return d;
        }
        for (String c : categories) {
            String d = parseItalianDate(c.replaceFirst("^Categoria:", ""));
            if (d != null) return d;
        }
        return null;
    }
}
