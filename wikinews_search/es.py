"""Elasticsearch index definition (mapping + analyzers) and the search query.

Every text field is indexed three times (multi-fields), each with its own analysis/ranking,
so the same index serves the three ranking models compared in the evaluation:
  italian      -> custom Italian analyzer (elision, lowercase, asciifolding, stopwords, light stemmer), BM25
  plain        -> standard analyzer + asciifolding, no stopwords/stemming,                          BM25
  lmdirichlet  -> same Italian analyzer, language-model similarity (LMDirichlet)
"""
import os
import time

from elasticsearch import Elasticsearch

ES_URL = os.environ.get("ES_URL", "http://localhost:9200")
INDEX = os.environ.get("WIKINEWS_INDEX", "wikinews")

SETTINGS = {
    "number_of_shards": 1,
    "number_of_replicas": 0,  # single-node cluster
    "similarity": {"lm": {"type": "LMDirichlet", "mu": 2000}},
    "analysis": {
        "filter": {
            "it_elision": {"type": "elision", "articles_case": True,
                           "articles": ["c", "l", "all", "dall", "dell", "nell", "sull", "coll", "pell",
                                        "gl", "agl", "dagl", "degl", "negl", "sugl", "un", "m", "t", "s", "v", "d"]},
            "it_stop": {"type": "stop", "stopwords": "_italian_"},
            "it_stemmer": {"type": "stemmer", "language": "light_italian"},
        },
        "analyzer": {
            "italian_custom": {"tokenizer": "standard",
                               "filter": ["it_elision", "lowercase", "asciifolding", "it_stop", "it_stemmer"]},
            "plain": {"tokenizer": "standard", "filter": ["lowercase", "asciifolding"]},
        },
    },
}


def _text():
    return {"type": "text", "analyzer": "italian_custom", "fields": {
        "plain": {"type": "text", "analyzer": "plain"},
        "lm": {"type": "text", "analyzer": "italian_custom", "similarity": "lm"},
    }}


MAPPINGS = {
    "dynamic": "strict",
    "properties": {
        "title": _text(),
        "body": _text() | {"term_vector": "with_positions_offsets"},  # fast highlighting on long articles
        "date": {"type": "date", "format": "yyyy-MM-dd"},
        "categories": {"type": "keyword"},
        "url": {"type": "keyword", "index": False},
    },
}

MODELS = {"italian": "", "plain": ".plain", "lmdirichlet": ".lm"}


def client(url: str = ES_URL) -> Elasticsearch:
    return Elasticsearch(url, request_timeout=60)


def create_index(es: Elasticsearch, index: str = INDEX) -> None:
    """(Re)create the index. Deletes only this index, never others."""
    es.indices.delete(index=index, ignore_unavailable=True)
    es.indices.create(index=index, settings=SETTINGS, mappings=MAPPINGS)


def build_query(q: str, model: str = "italian", title_boost: float = 3.0,
                date_from: str | None = None, date_to: str | None = None) -> dict:
    """User query -> ES bool query.

    simple_query_string supports "phrase", -exclusion, a | b, prefix* and never raises on bad syntax,
    so user input is safe to pass through. Words are ANDed (default_operator).
    """
    suffix = MODELS[model]
    query = {"bool": {"must": [{"simple_query_string": {
        "query": q,
        "fields": [f"title{suffix}^{title_boost}", f"body{suffix}"],
        "default_operator": "and",
        "flags": "AND|OR|NOT|PHRASE|PREFIX|PRECEDENCE|WHITESPACE",
    }}]}}
    if date_from or date_to:
        query["bool"]["filter"] = [{"range": {"date": {k: v for k, v in (("gte", date_from), ("lte", date_to)) if v}}}]
    return query


def search(es: Elasticsearch, q: str, page: int = 1, size: int = 10, model: str = "italian",
           sort: str = "relevance", date_from: str | None = None, date_to: str | None = None,
           title_boost: float = 3.0, index: str = INDEX) -> dict:
    t0 = time.perf_counter()
    if not q.strip():
        return {"query": q, "total": 0, "results": [], "ms": 0.0}
    suffix = MODELS[model]
    res = es.search(
        index=index,
        query=build_query(q, model, title_boost, date_from, date_to),
        from_=(page - 1) * size,
        size=size,
        sort=[{"date": {"order": "desc", "missing": "_last"}}, "_score"] if sort == "date" else None,
        track_total_hits=True,
        source=["title", "url", "date"],
        highlight={"encoder": "html",  # escapes the article text, only <mark> is added
                   "pre_tags": ["<mark>"], "post_tags": ["</mark>"],
                   "fields": {f"body{suffix}": {"fragment_size": 200, "number_of_fragments": 2,
                                                "no_match_size": 200}}},
    )
    results = [{
        "id": hit["_id"],
        "title": hit["_source"]["title"],
        "url": hit["_source"]["url"],
        "date": hit["_source"].get("date"),
        "score": round(hit["_score"], 3) if hit["_score"] is not None else None,
        "snippet": " … ".join(hit.get("highlight", {}).get(f"body{suffix}", [])),
    } for hit in res["hits"]["hits"]]
    return {"query": q, "total": res["hits"]["total"]["value"], "results": results,
            "ms": round((time.perf_counter() - t0) * 1000, 1), "es_took_ms": res["took"]}
