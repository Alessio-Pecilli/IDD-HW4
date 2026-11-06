"""Index the Wikimedia Enterprise HTML dump into Elasticsearch.

Usage: python -m wikinews_search.indexer data/itwikinews.json.tar.gz [--index wikinews] [--limit N]
"""
import argparse
import json
import sys
import tarfile
import time

from elasticsearch import helpers

from . import es as es_mod
from .text import extract_date, html_to_text


def iter_records(dump_path: str):
    """Yield the JSON records of every ndjson file in the tar.gz (streamed, never fully in memory)."""
    with tarfile.open(dump_path, "r:gz") as tar:
        for member in tar:
            if member.isfile() and member.name.endswith(".ndjson"):
                for line in tar.extractfile(member):
                    if line.strip():
                        yield json.loads(line)


def to_doc(rec: dict) -> dict | None:
    """Dump record -> ES document, or None when it has no usable text."""
    body = html_to_text(rec.get("article_body", {}).get("html", ""))
    if not body:
        return None
    categories = [c["name"].removeprefix("Categoria:") for c in rec.get("categories", [])]
    return {
        "title": rec["name"],
        "url": rec["url"],
        "date": extract_date(rec.get("article_body", {}).get("wikitext", ""), categories),
        "categories": categories,
        "body": body,
    }


def actions(dump_path: str, index: str, limit: int | None, stats: dict):
    for rec in iter_records(dump_path):
        doc = to_doc(rec)
        if doc is None:
            stats["empty"] += 1
            continue
        yield {
            "_index": index,
            "_id": rec["identifier"],
            # the dump contains several revisions of some pages: with external versioning
            # (version = revision id) ES itself rejects any revision older than the indexed one
            "_version": rec.get("version", {}).get("identifier", 1),
            "_version_type": "external",
            "_source": doc,
        }
        stats["read"] += 1
        if limit and stats["read"] >= limit:
            return


def build_index(dump_path: str, es=None, index: str = es_mod.INDEX, limit: int | None = None) -> dict:
    es = es or es_mod.client()
    es_mod.create_index(es, index)
    stats = {"read": 0, "empty": 0, "indexed": 0, "older_revisions": 0}
    t0 = time.time()
    for ok, item in helpers.streaming_bulk(es, actions(dump_path, index, limit, stats), chunk_size=200,
                                           raise_on_error=False, raise_on_exception=True):
        if ok:
            stats["indexed"] += 1
        elif item["index"].get("status") == 409:  # version conflict = older revision
            stats["older_revisions"] += 1
        else:
            raise RuntimeError(f"indexing failed: {item}")
        if ok and stats["indexed"] % 1000 == 0:
            print(f"{stats['indexed']} documents indexed ({time.time() - t0:.0f}s)", file=sys.stderr)
    es.indices.refresh(index=index)
    stats["documents"] = es.count(index=index)["count"]
    stats["seconds"] = round(time.time() - t0, 1)
    print(f"done: {stats}", file=sys.stderr)
    return stats


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("dump", help="itwikinews-NS0-...-ENTERPRISE-HTML.json.tar.gz")
    p.add_argument("--index", default=es_mod.INDEX)
    p.add_argument("--es-url", default=es_mod.ES_URL)
    p.add_argument("--limit", type=int, help="index only the first N documents")
    a = p.parse_args()
    build_index(a.dump, es_mod.client(a.es_url), a.index, a.limit)


if __name__ == "__main__":
    main()
