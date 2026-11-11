# Risultati sperimentali

Indice: 12211 documenti · Apache Lucene 10.6.0 · known-item: 300 articoli campionati (seed 42) · topical: 20 query giudicate tramite categorie Wikinews.

| Configurazione | Titolo S@1 | Titolo MRR@10 | Parole S@1 | Parole S@10 | Parole MRR@10 | Topical P@10 | Topical nDCG@10 | Latenza media (ms) | Latenza p95 (ms) |
|---|---|---|---|---|---|---|---|---|---|
| BM25 + analyzer italiano, title^3 | 0,957 | 0,975 | 0,670 | 0,860 | 0,734 | 0,790 | 0,793 | 0,6 | 1,4 |
| BM25 + analyzer italiano, no boost | 0,873 | 0,907 | 0,690 | 0,860 | 0,745 | 0,780 | 0,784 | 0,4 | 0,9 |
| BM25 standard (no stemming), title^3 | 0,983 | 0,990 | 0,620 | 0,780 | 0,672 | 0,745 | 0,765 | 0,4 | 0,8 |
| LM Dirichlet + analyzer italiano, title^3 | 0,837 | 0,874 | 0,640 | 0,847 | 0,707 | 0,765 | 0,746 | 0,3 | 0,7 |

Latenza = tempo di retrieval + ranking top-10 nel processo Java (senza snippet), dopo il warm-up.
