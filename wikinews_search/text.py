"""Document preparation: visible text from the Parsoid HTML and publication date.
Tokenisation, stemming and highlighting are done by Elasticsearch analyzers (see es.py)."""
import re

import lxml.html

MONTHS = {m: i for i, m in enumerate(
    ["gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio",
     "agosto", "settembre", "ottobre", "novembre", "dicembre"], start=1)}
DATE_RE = re.compile(r"(\d{1,2})\s*[º°]?\s+([a-zA-Z]+)\s+(\d{4})")
DATA_TEMPLATE_RE = re.compile(r"\{\{\s*[Dd]ata\s*\|\s*([^|}]+)")
BLOCK_TAGS = ("p", "br", "li", "td", "th", "tr", "div", "h1", "h2", "h3", "h4", "h5", "h6", "section", "dd", "dt")


def html_to_text(page_html: str) -> str:
    """Visible text of a Parsoid HTML page, one line per block element."""
    if not page_html:
        return ""
    root = lxml.html.fromstring(page_html)
    for bad in root.xpath("//style|//script|//link|//meta|//head"):
        bad.drop_tree()
    for el in root.iter(*BLOCK_TAGS):
        el.tail = "\n" + (el.tail or "")
    text = root.text_content()
    lines = (" ".join(line.split()) for line in text.splitlines())
    return "\n".join(line for line in lines if line)


def parse_italian_date(s: str) -> str | None:
    """'14 gennaio 2007' -> '2007-01-14'."""
    m = DATE_RE.search(s or "")
    if not m or m.group(2).lower() not in MONTHS:
        return None
    day, month, year = int(m.group(1)), MONTHS[m.group(2).lower()], int(m.group(3))
    if not 1 <= day <= 31:
        return None
    return f"{year:04d}-{month:02d}-{day:02d}"


def extract_date(wikitext: str, categories: list[str]) -> str | None:
    """Publication date from the {{Data|...}} template, falling back to the date category."""
    m = DATA_TEMPLATE_RE.search(wikitext or "")
    if m and (d := parse_italian_date(m.group(1))):
        return d
    for c in categories:
        if d := parse_italian_date(c.removeprefix("Categoria:")):
            return d
    return None
