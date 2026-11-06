# Risultati sperimentali

Indice `wikinews`: 12211 documenti · Elasticsearch 9.5.0 (Lucene 10.5.0) · known-item: 300 articoli campionati (seed 42) · topical: 20 query giudicate tramite categorie Wikinews.

| Configurazione | Titolo S@1 | Titolo MRR@10 | Parole S@1 | Parole S@10 | Parole MRR@10 | Topical P@10 | Topical nDCG@10 | Latenza media (ms) | Latenza p95 (ms) |
|---|---|---|---|---|---|---|---|---|---|
| BM25 + analyzer italiano, title^3 | 0.947 | 0.962 | 0.657 | 0.873 | 0.724 | 0.790 | 0.793 | 3.4 | 6 |
| BM25 + analyzer italiano, no boost | 0.883 | 0.915 | 0.663 | 0.873 | 0.731 | 0.780 | 0.784 | 2.0 | 3 |
| BM25 standard (no stemming), title^3 | 0.963 | 0.975 | 0.640 | 0.810 | 0.700 | 0.745 | 0.765 | 1.4 | 3 |
| LM Dirichlet + analyzer italiano, title^3 | 0.827 | 0.866 | 0.623 | 0.850 | 0.699 | 0.765 | 0.746 | 1.2 | 2 |

Latenza = `took` di Elasticsearch (solo retrieval + ranking, top-10, senza highlighting).
