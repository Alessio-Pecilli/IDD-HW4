# IDD-HW4 · Motore di ricerca per Wikinews in italiano

Sistema di ricerca full-text, con interfaccia web, sulle notizie di [it.wikinews.org](https://it.wikinews.org/wiki/)
contenute nel dump Wikimedia Enterprise
[`itwikinews-NS0-20250320-ENTERPRISE-HTML.json.tar.gz`](https://dumps.wikimedia.org/other/enterprise_html/runs/20250320/itwikinews-NS0-20250320-ENTERPRISE-HTML.json.tar.gz).

**Autori:** Alessio Pecilli, Matteo Cerretani, Alessio Natale Scarpa (corso di Ingegneria dei Dati, Homework 4).

Il motore è **Elasticsearch 9.5** (basato su **Apache Lucene 10**). Indicizzazione, interfaccia web e valutazione sono scritte in Python.

## Architettura

```
dump .tar.gz ──► indexer.py ──────────────► Elasticsearch (indice "wikinews") ◄── app.py (FastAPI) ◄── browser
  (ndjson,        estrazione testo da HTML,     mapping + analyzer italiano          /api/search
   HTML Parsoid)  data, categorie, bulk          BM25 / LM Dirichlet, highlight       pagina web statica
```

**Indicizzazione** (`wikinews_search/indexer.py`, `text.py`)
- Il dump (590 MB di ndjson) viene letto in streaming dal `.tar.gz`, senza decomprimerlo su disco.
- Per ogni pagina si estrae il testo visibile dall'HTML Parsoid (con lxml, scartando style, script e metadati).
  La data di pubblicazione si ricava dal template `{{Data|…}}` del wikitesto, oppure dalla categoria-data.
- Il caricamento usa la **bulk API** (`helpers.streaming_bulk`). Il dump contiene più revisioni della stessa pagina.
  Per tenere solo la più recente si usa il **versioning esterno** di ES: `_version` è l'id della revisione.
  Elasticsearch rifiuta da solo le revisioni più vecchie (errore 409).

**Mapping** (`wikinews_search/es.py`)

| Campo | Tipo | Note |
|---|---|---|
| `title`, `body` | `text` | analyzer `italian_custom`: tokenizer standard → elision (l', dell', …) → lowercase → asciifolding → stopword italiane → stemmer `light_italian` |
| `title.plain`, `body.plain` | `text` (multi-field) | analyzer standard + asciifolding, senza stemming né stopword: serve da baseline |
| `title.lm`, `body.lm` | `text` (multi-field) | analyzer italiano con similarity **LMDirichlet** (μ = 2000) |
| `date` | `date` | filtri `range` e ordinamento |
| `categories` | `keyword` | aggregazioni; giudizi di rilevanza nella valutazione |
| `url` | `keyword`, non indicizzato | solo memorizzato |

`body` ha anche i `term_vector` con offset, così l'highlighting resta veloce sugli articoli lunghi.

**Ricerca**
- `simple_query_string` su `title^3` e `body`, combinati con `default_operator: and`.
  Accetta frasi esatte, esclusioni, OR e prefissi, e non va mai in errore su input malformato.
- Filtro `range` sulla data dentro un `bool` (in contesto *filter*, quindi non incide sullo score).
- Ordinamento per rilevanza (BM25 di default) oppure per data.
- Snippet generati dall'highlighter di ES, con `encoder: html`: il testo è escapato e vengono aggiunti solo i tag `<mark>`.

## Installazione

Requisiti: Python ≥ 3.10, Docker (con Compose v2), circa 3 GB di RAM liberi per Elasticsearch.

1. Clona il repository e installa le dipendenze Python:

   ```bash
   git clone https://github.com/Alessio-Pecilli/IDD-HW4.git
   ```

   ```bash
   cd IDD-HW4
   ```

   ```bash
   pip install -r requirements.txt
   ```

2. Avvia Elasticsearch 9.5.0 (single node, sicurezza disabilitata, solo per uso locale):

   ```bash
   docker compose up -d --wait
   ```

   Kibana è opzionale: `docker compose --profile kibana up -d`, poi apri http://localhost:5601.

3. Scarica il dump (73 MB):

   ```bash
   curl -o data/itwikinews.json.tar.gz --create-dirs https://dumps.wikimedia.org/other/enterprise_html/runs/20250320/itwikinews-NS0-20250320-ENTERPRISE-HTML.json.tar.gz
   ```

4. Costruisci l'indice (circa 40 secondi, 12.211 articoli):

   ```bash
   python -m wikinews_search.indexer data/itwikinews.json.tar.gz
   ```

   Opzioni: `--index NOME`, `--es-url URL`, `--limit N` (indicizza solo i primi N documenti).

## Utilizzo

```bash
python -m uvicorn wikinews_search.app:app --port 8000
```

Poi apri http://localhost:8000.

### Sintassi delle query

| Esempio | Significato |
|---|---|
| `terremoto aquila` | tutte le parole (AND) |
| `"presidente della repubblica"` | frase esatta |
| `calcio -juventus` | esclude i documenti con *juventus* |
| `obama \| mccain` | OR |
| `olimp*` | prefisso |

Dall'interfaccia puoi anche scegliere il modello di ranking (BM25 con analyzer italiano, BM25 senza stemming,
LM Dirichlet), l'ordinamento (rilevanza o data) e un intervallo di date.

### API JSON

- `GET /api/search?q=…&page=1&size=10&model=italian|plain|lmdirichlet&sort=relevance|date&date_from=YYYY-MM-DD&date_to=YYYY-MM-DD`
- `GET /api/stats`: numero di documenti, intervallo di date e versione di Lucene.
- `GET /docs`: documentazione OpenAPI (Swagger).

Le variabili d'ambiente `ES_URL` (default `http://localhost:9200`) e `WIKINEWS_INDEX` (default `wikinews`)
permettono di puntare a un altro cluster o indice.

## Strategia di test

Il sistema è verificato su tre livelli funzionali, più una valutazione sperimentale.

| Livello | File | Cosa verifica | Dipendenze |
|---|---|---|---|
| Unit | `tests/test_text.py` | estrazione del testo dall'HTML, parsing delle date italiane, costruzione della query ES (campi per modello, filtro date) | nessuna |
| Integrazione | `tests/test_search.py` | indicizza un **mini-dump sintetico** (generato al volo come `.tar.gz`) in un indice usa-e-getta `wikinews_test`, poi controlla: pagine vuote scartate, revisione più recente mantenuta, stemming (*terremoto* trova *terremoti*), accenti ed elisioni, frasi, AND/OR/NOT, prefissi, title boost, LM Dirichlet, filtro e ordinamento per data, paginazione, highlighting | Elasticsearch |
| End-to-end | `tests/test_api.py` | endpoint HTTP tramite `TestClient`: pagina web, statistiche, validazione degli input (422), query ostili (sintassi rotta, tentativi di injection, `<script>`) che non devono mai causare errori, snippet escapati | Elasticsearch |
| Valutazione | `eval/evaluate.py` | efficacia e latenza sull'indice reale | indice completo |

Per eseguire i test (servono Elasticsearch attivo, ma **non** il dump):

```bash
python -m pytest -v
```

Se Elasticsearch non è raggiungibile, i test di integrazione ed end-to-end vengono saltati e restano solo gli unit test.

### Valutazione sperimentale

```bash
python eval/evaluate.py
```

Sul dump non ci sono giudizi di rilevanza manuali, quindi si usano due collezioni di test giudicate automaticamente.

1. **Known-item search.** Si estraggono 300 articoli a caso; ognuno deve essere ritrovato
   (a) cercandone il titolo e (b) cercando 4 parole casuali del suo corpo.
   Metriche: Success@1, Success@10, MRR@10.
2. **Query tematiche.** 20 query in linguaggio naturale. Un documento è rilevante se ha la categoria Wikinews
   corrispondente, assegnata dai redattori (es. *"contagi coronavirus"* → `COVID-19`).
   Metriche: P@10, nDCG@10.

Le configurazioni confrontate sono quattro: analyzer (italiano o standard), title boost (3 o 1) e ranking (BM25 o LM Dirichlet).
I risultati vengono scritti in [`eval/results.md`](eval/results.md):

| Configurazione | Titolo S@1 | Titolo MRR@10 | Parole S@1 | Parole S@10 | Parole MRR@10 | Topical P@10 | Topical nDCG@10 | Latenza media (ms) | p95 (ms) |
|---|---|---|---|---|---|---|---|---|---|
| BM25 + analyzer italiano, title^3 | 0.947 | 0.962 | 0.657 | 0.873 | 0.724 | **0.790** | **0.793** | 3.4 | 6 |
| BM25 + analyzer italiano, no boost | 0.883 | 0.915 | **0.663** | **0.873** | **0.731** | 0.780 | 0.784 | 2.0 | 3 |
| BM25 standard (no stemming), title^3 | **0.963** | **0.975** | 0.640 | 0.810 | 0.700 | 0.745 | 0.765 | 1.4 | 3 |
| LM Dirichlet + analyzer italiano, title^3 | 0.827 | 0.866 | 0.623 | 0.850 | 0.699 | 0.765 | 0.746 | 1.2 | 2 |

Cosa emerge:
- L'analyzer italiano migliora le **query tematiche** (P@10 da 0.745 a 0.790) e la ricerca per parole del corpo
  (S@10 da 0.810 a 0.873), perché stemming ed elisione fanno corrispondere le forme flesse.
- Sulle **query-titolo** la corrispondenza esatta senza stemming è leggermente migliore, probabilmente perché lo stemming rende ambigui
  titoli molto simili, come le giornate di campionato.
- Il **boost del titolo** aiuta molto la ricerca navigazionale (S@1 da 0.883 a 0.947) e penalizza poco le query sul contenuto.
- Su questo corpus **BM25 supera LM Dirichlet**.
- Su 12 mila documenti tutte le configurazioni rispondono in pochi millisecondi.

## Struttura del progetto

```
wikinews_search/
  text.py        estrazione del testo da HTML e parsing delle date
  es.py          mapping, analyzer e similarity dell'indice; costruzione ed esecuzione delle query
  indexer.py     lettura in streaming del dump e indicizzazione bulk
  app.py         API FastAPI
  static/        interfaccia web (HTML + JS, senza build)
tests/           unit, integrazione ed end-to-end (pytest)
eval/            valutazione sperimentale e risultati
docker-compose.yml   Elasticsearch 9.5.0 (+ Kibana opzionale), da tteofili/lucenex
```

## Riferimenti

- Slide del corso *Ingegneria dei Dati*: "Lucene & Elasticsearch" (2025)
- [tteofili/lucenex](https://github.com/tteofili/lucenex): esempi Lucene e setup Docker di Elasticsearch
- [Elasticsearch: text analysis](https://www.elastic.co/docs/reference/text-analysis), [similarity](https://www.elastic.co/docs/reference/elasticsearch/index-settings/similarity)
