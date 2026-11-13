package it.uniroma3.idd.wikinews;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Test unitari per TextExtractor: verifica parsing HTML e formati di date italiane senza indice Lucene. */
class TextExtractorTest {

    @Test
    void htmlToTextKeepsVisibleTextOneLinePerBlock() {
        String text = TextExtractor.htmlToText("<html><head><title>T</title><style>.a{color:red}</style></head>"
                + "<body><h2>Titolo</h2><p>Primo <b>paragrafo</b>.</p><ul><li>uno</li><li>due</li></ul></body></html>");
        assertEquals(List.of("Titolo", "Primo paragrafo.", "uno", "due"), text.lines().toList());
        assertFalse(text.contains("color"));
    }

    @Test
    void htmlToTextEmpty() {
        assertEquals("", TextExtractor.htmlToText(""));
        assertEquals("", TextExtractor.htmlToText(null));
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "14 gennaio 2007, 2007-01-14",
            "1º maggio 2010, 2010-05-01",
            "3 Dicembre 2015, 2015-12-03",
            "14 foo 2007, NULL",
            "40 gennaio 2007, NULL",
            "'', NULL"})
    void parseItalianDate(String input, String expected) {
        assertEquals(expected, TextExtractor.parseItalianDate(input));
    }

    @Test
    void extractDatePrefersTemplateThenCategory() {
        assertEquals("2020-03-05", TextExtractor.extractDate("{{Data|5 marzo 2020}} testo", List.of("1 gennaio 2000")));
        assertEquals("2019-07-07", TextExtractor.extractDate("nessun template", List.of("Italia", "Categoria:7 luglio 2019")));
        assertNull(TextExtractor.extractDate("", List.of()));
    }
}
