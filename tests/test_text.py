"""Unit tests (no Elasticsearch needed): text extraction, date parsing, query building."""
import pytest

from wikinews_search.es import build_query
from wikinews_search.text import extract_date, html_to_text, parse_italian_date


def test_html_to_text_drops_markup_and_style():
    text = html_to_text("<html><head><title>T</title><style>.a{color:red}</style></head>"
                        "<body><h2>Titolo</h2><p>Primo <b>paragrafo</b>.</p><ul><li>uno</li><li>due</li></ul></body></html>")
    assert text.splitlines() == ["Titolo", "Primo paragrafo .", "uno", "due"] or text.splitlines() == ["Titolo", "Primo paragrafo.", "uno", "due"]
    assert "color" not in text and "T" not in text.split()


def test_html_to_text_empty():
    assert html_to_text("") == ""


@pytest.mark.parametrize("s,expected", [
    ("14 gennaio 2007", "2007-01-14"),
    ("1º maggio 2010", "2010-05-01"),
    ("3 Dicembre 2015", "2015-12-03"),
    ("14 foo 2007", None),
    ("40 gennaio 2007", None),
    ("", None),
])
def test_parse_italian_date(s, expected):
    assert parse_italian_date(s) == expected


def test_extract_date_prefers_template_then_category():
    assert extract_date("{{Data|5 marzo 2020}} testo", ["Categoria:1 gennaio 2000"]) == "2020-03-05"
    assert extract_date("nessun template", ["Categoria:Italia", "Categoria:7 luglio 2019"]) == "2019-07-07"
    assert extract_date("", []) is None


def test_build_query_fields_per_model():
    q = build_query("roma", "plain", title_boost=2.0)
    assert q["bool"]["must"][0]["simple_query_string"]["fields"] == ["title.plain^2.0", "body.plain"]
    assert build_query("roma", "lmdirichlet")["bool"]["must"][0]["simple_query_string"]["fields"][1] == "body.lm"
    assert "filter" not in q["bool"]


def test_build_query_date_range():
    q = build_query("roma", date_from="2010-01-01")
    assert q["bool"]["filter"] == [{"range": {"date": {"gte": "2010-01-01"}}}]
