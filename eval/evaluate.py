"""Effectiveness + efficiency evaluation of the search system on the real index.

Two automatically judged test collections (no manual labelling needed):
  1. Known-item search: N random articles; each one must be found by
       a) its title             (navigational query)
       b) 4 random words (>=5 letters) taken from its body   (content query)
     metrics: Success@1, Success@10, MRR@10
  2. Topical queries: 20 natural-language queries, a document is relevant if it carries
     the matching Wikinews category (editor-assigned labels used as relevance judgments).
     metrics: P@10, nDCG@10 (binary gains)
Configurations compared: ranking model/analyzer and title boost. Latency: mean / p95 per query.

Usage: python eval/evaluate.py [--n 300] [--out eval/results.md]
"""
import argparse
import math
import random
import re
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from wikinews_search import es as m  # noqa: E402

TOPICAL = [  # query -> relevant category
    ("terremoto", "Terremoti"),
    ("incidente aereo", "Incidenti aerei"),
    ("elezioni politiche 2006", "Elezioni politiche italiane 2006"),
    ("gran premio formula 1", "Formula 1"),
    ("tappa giro d'italia", "Giro d'Italia"),
    ("olimpiadi pechino", "XXIX Olimpiade estiva"),
    ("papa benedetto", "Benedetto XVI"),
    ("contagi coronavirus", "COVID-19"),
    ("guerra ucraina", "Ucraina"),
    ("missione spaziale", "Astronautica"),
    ("torneo di tennis", "Tennis"),
    ("incendio", "Incendi"),
    ("attentato bomba", "Attentati"),
    ("mondiali di calcio 2010", "Campionato mondiale di calcio 2010"),
    ("omicidio", "Omicidi"),
    ("berlusconi", "Silvio Berlusconi"),
    ("mostra", "Mostre"),
    ("film", "Cinema"),
    ("pallanuoto", "Pallanuoto"),
    ("estrazione superenalotto", "Superenalotto"),
]

CONFIGS = [  # name, model, title boost
    ("BM25 + analyzer italiano, title^3", "italian", 3.0),
    ("BM25 + analyzer italiano, no boost", "italian", 1.0),
    ("BM25 standard (no stemming), title^3", "plain", 3.0),
    ("LM Dirichlet + analyzer italiano, title^3", "lmdirichlet", 3.0),
]


def run(es, index, q, model, boost):
    # hits only (no highlight) so latency reflects retrieval + ranking
    res = es.search(index=index, query=m.build_query(q, model, boost), size=10, source=["categories"])
    return res["hits"]["hits"], res["took"]


def sample_docs(es, index, n, seed):
    res = es.search(index=index, size=n, source=["title", "body"],
                    query={"function_score": {"query": {"match_all": {}},
                                              "random_score": {"seed": seed, "field": "_seq_no"}}})
    rng = random.Random(seed)
    out = []
    for h in res["hits"]["hits"]:
        words = re.findall(r"[^\W\d_]{5,}", h["_source"]["body"])
        if len(h["_source"]["title"].split()) >= 3 and len(words) >= 20:
            out.append((h["_id"], h["_source"]["title"], " ".join(rng.sample(words, 4))))
    return out


def known_item(es, index, docs, model, boost, which, took):
    ranks = []
    for doc_id, title, words in docs:
        hits, t = run(es, index, title if which == "title" else words, model, boost)
        took.append(t)
        ids = [h["_id"] for h in hits]
        ranks.append(ids.index(doc_id) + 1 if doc_id in ids else None)
    n = len(ranks)
    return {"S@1": sum(r == 1 for r in ranks) / n,
            "S@10": sum(r is not None for r in ranks) / n,
            "MRR@10": sum(1 / r for r in ranks if r) / n}


def topical(es, index, model, boost, took):
    p10, ndcg = [], []
    for q, cat in TOPICAL:
        hits, t = run(es, index, q, model, boost)
        took.append(t)
        rel = [cat in h["_source"].get("categories", []) for h in hits] + [False] * (10 - len(hits))
        n_rel = es.count(index=index, query={"term": {"categories": cat}})["count"]
        dcg = sum(r / math.log2(i + 2) for i, r in enumerate(rel))
        idcg = sum(1 / math.log2(i + 2) for i in range(min(10, n_rel)))
        p10.append(sum(rel) / 10)
        ndcg.append(dcg / idcg if idcg else 0.0)
    return {"P@10": statistics.mean(p10), "nDCG@10": statistics.mean(ndcg)}


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--n", type=int, default=300, help="sampled documents for known-item search")
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--index", default=m.INDEX)
    p.add_argument("--out", default="eval/results.md")
    a = p.parse_args()
    es = m.client()
    docs = sample_docs(es, a.index, a.n, a.seed)

    rows = []
    for name, model, boost in CONFIGS:
        took = []
        kt = known_item(es, a.index, docs, model, boost, "title", took)
        kw = known_item(es, a.index, docs, model, boost, "words", took)
        tp = topical(es, a.index, model, boost, took)
        took.sort()
        rows.append((name, kt, kw, tp, statistics.mean(took), took[int(0.95 * (len(took) - 1))]))
        print(f"{name}: title {kt} | words {kw} | topical {tp}", file=sys.stderr)

    info = es.info()["version"]
    lines = [
        "# Risultati sperimentali",
        "",
        f"Indice `{a.index}`: {es.count(index=a.index)['count']} documenti · Elasticsearch {info['number']} "
        f"(Lucene {info['lucene_version']}) · known-item: {len(docs)} articoli campionati (seed {a.seed}) · "
        f"topical: {len(TOPICAL)} query giudicate tramite categorie Wikinews.",
        "",
        "| Configurazione | Titolo S@1 | Titolo MRR@10 | Parole S@1 | Parole S@10 | Parole MRR@10 "
        "| Topical P@10 | Topical nDCG@10 | Latenza media (ms) | Latenza p95 (ms) |",
        "|---|---|---|---|---|---|---|---|---|---|",
    ]
    for name, kt, kw, tp, mean, p95 in rows:
        lines.append(f"| {name} | {kt['S@1']:.3f} | {kt['MRR@10']:.3f} | {kw['S@1']:.3f} | {kw['S@10']:.3f} "
                     f"| {kw['MRR@10']:.3f} | {tp['P@10']:.3f} | {tp['nDCG@10']:.3f} | {mean:.1f} | {p95} |")
    lines += ["", "Latenza = `took` di Elasticsearch (solo retrieval + ranking, top-10, senza highlighting).", ""]
    Path(a.out).write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
