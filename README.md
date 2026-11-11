# IDD-HW4 · Motore di ricerca per Wikinews in italiano

Sistema di ricerca full-text, con interfaccia web, sulle notizie di [it.wikinews.org](https://it.wikinews.org/wiki/)
contenute nel dump Wikimedia Enterprise
[`itwikinews-NS0-20250320-ENTERPRISE-HTML.json.tar.gz`](https://dumps.wikimedia.org/other/enterprise_html/runs/20250320/itwikinews-NS0-20250320-ENTERPRISE-HTML.json.tar.gz).

**Autori:** Alessio Pecilli, Matteo Cerretani, Alessio Natale Scarpa (corso di Ingegneria dei Dati, Homework 4).

Scritto in **Java 21** con **Apache Lucene 10.6** usato direttamente, come negli esempi di
[tteofili/lucenex](https://github.com/tteofili/lucenex). Non servono servizi esterni: l'indice è una cartella su disco,
e la pagina web è servita dal server HTTP incluso nel JDK.

## Architettura

```
dump .tar.gz ──► WikinewsIndexer ──► indice Lucene (data/index) ◄── WikinewsSearcher ◄── WebServer ◄── browser
 (ndjson,         estrazione testo,     IndexWriter                  SimpleQueryParser      /api/search
  HTML Parsoid)   data, revisioni       PerFieldAnalyzerWrapper      BM25 / LM Dirichlet    pagina statica
                                                                     UnifiedHighlighter
```

**Indicizzazione** (`WikinewsIndexer`, `TextExtractor`)
- Il dump (590 MB di ndjson) è letto in streaming dal `.tar.gz` (commons-compress), senza decomprimerlo su disco.
- Per ogni pagina si estrae il testo visibile dall'HTML Parsoid (jsoup, scartando style, script e metadati).
  La data di pubblicazione viene dal template `{{Data|…}}` del wikitesto o, in mancanza, dalla categoria-data.
- Il dump contiene più revisioni della stessa pagina. L'indexer tiene la più recente (id di revisione più alto)
  con `IndexWriter.updateDocument`, che in Lucene equivale a delete + add.

**Campi e analyzer** (`Analyzers`, un `PerFieldAnalyzerWrapper`)

| Campo | Tipo Lucene | Analisi |
|---|---|---|
| `title`, `body` | `TextField` (stored) | analyzer italiano: `StandardTokenizer` → `ElisionFilter` (l', dell', …) → `LowerCaseFilter` → `ASCIIFoldingFilter` → `StopFilter` (stopword italiane) → `ItalianLightStemFilter` |
| `title_plain`, `body_plain` | `TextField` | `StandardTokenizer` + minuscole + asciifolding, senza stemming né stopword (baseline) |
| `date` | `KeywordField` | filtro `TermRangeQuery` e ordinamento (date ISO: ordine alfabetico = ordine temporale) |
| `categories` | `KeywordField` | giudizi di rilevanza nella valutazione |
| `id`, `url` | `KeywordField` / `StoredField` | identità del documento e link |

`body` è indicizzato con gli *offset* nelle posting list, così lo `UnifiedHighlighter` produce gli snippet senza rianalizzare il testo.

**Ricerca** (`WikinewsSearcher`)
- `SimpleQueryParser` su `title^3` e `body`, con operatore di default `MUST` (tutte le parole).
  Accetta frasi, esclusioni, OR e prefissi, e non lancia mai eccezioni su input malformato.
- Filtro sulle date come clausola `FILTER` di una `BooleanQuery`, quindi non incide sullo score.
- Ranking: `BM25Similarity` (default di Lucene) oppure `LMDirichletSimilarity` (μ = 2000), su due `IndexSearcher` dello stesso indice.
- Snippet: `UnifiedHighlighter` con `DefaultPassageFormatter` che fa l'escape dell'HTML e aggiunge solo i tag `<mark>`.

## Installazione

Requisiti: **JDK 21 o superiore**. Maven non serve: il repository include il Maven Wrapper (`mvnw`).

1. Clona il repository e compila: i test vengono eseguiti e si ottiene un unico jar eseguibile.

   ```bash
   git clone https://github.com/Alessio-Pecilli/IDD-HW4.git
   ```

   ```bash
   cd IDD-HW4
   ```

   ```bash
   ./mvnw package
   ```

   Su Windows (cmd o PowerShell) usa `mvnw.cmd package`.

2. Scarica il dump (73 MB):

   ```bash
   curl -o data/itwikinews.json.tar.gz --create-dirs https://dumps.wikimedia.org/other/enterprise_html/runs/20250320/itwikinews-NS0-20250320-ENTERPRISE-HTML.json.tar.gz
   ```

3. Costruisci l'indice (circa 20 secondi, 12.211 articoli, 41 MB in `data/index`):

   ```bash
   java -jar target/wikinews-search.jar index data/itwikinews.json.tar.gz
   ```

   Opzioni: una cartella diversa per l'indice come secondo argomento, e `--limit N` per indicizzare solo i primi N record.

## Utilizzo

```bash
java -jar target/wikinews-search.jar serve
```

Poi apri http://localhost:8000. Usa `--port N` per cambiare porta; il server ascolta solo su localhost.

### Sintassi delle query

| Esempio | Significato |
|---|---|
| `terremoto aquila` | tutte le parole (AND) |
| `"presidente della repubblica"` | frase esatta |
| `calcio -juventus` | esclude i documenti con *juventus* |
| `obama \| mccain` | OR |
| `olimp*` | prefisso |

Dalla pagina puoi anche scegliere il modello di ranking (BM25 con analyzer italiano, BM25 senza stemming,
LM Dirichlet), l'ordinamento (rilevanza o data) e un intervallo di date. Lo stile grafico riprende i token di
iadmin-core-frontend, in versione chiara e scura.

### API JSON

- `GET /api/search?q=…&page=1&size=10&model=italian|plain|lmdirichlet&sort=relevance|date&date_from=YYYY-MM-DD&date_to=YYYY-MM-DD`
- `GET /api/stats`: numero di documenti, intervallo di date e versione di Lucene.

Parametri non validi restituiscono `422` con un campo `detail` che spiega l'errore.

## Strategia di test

| Livello | Classe | Cosa verifica |
|---|---|---|
| Unit | `TextExtractorTest` | estrazione del testo dall'HTML, parsing delle date italiane, scelta tra template e categoria |
| Integrazione | `SearchTest` | indicizza un **mini-dump sintetico** (generato al volo come `.tar.gz`) in un indice temporaneo e controlla: pipeline dell'analyzer, pagine vuote scartate, revisione più recente mantenuta, stemming (*terremoto* trova *terremoti*), accenti ed elisioni, frasi, AND/OR/NOT, prefissi, title boost, LM Dirichlet, filtro e ordinamento per data, paginazione, highlighting |
| End-to-end | `WebServerTest` | richieste HTTP reali: pagina web, statistiche, validazione degli input (422), query ostili (sintassi rotta, tentativi di injection, `<script>`) che non devono mai causare errori, snippet escapati |
| Valutazione | `Evaluator` | efficacia e latenza sull'indice reale |

I 29 test JUnit 5 partono con `./mvnw test` (o durante `./mvnw package`) e non richiedono il dump.

### Valutazione sperimentale

```bash
java -jar target/wikinews-search.jar eval
```

Il dump non ha giudizi di rilevanza manuali, quindi si usano due collezioni di test giudicate automaticamente.

1. **Known-item search.** 300 articoli estratti a caso (seed 42) devono essere ritrovati
   (a) cercandone il titolo e (b) cercando 4 parole casuali del loro testo. Metriche: Success@1, Success@10, MRR@10.
2. **Query tematiche.** 20 query in linguaggio naturale. Un documento è rilevante se ha la categoria Wikinews
   corrispondente, assegnata dai redattori (es. *"contagi coronavirus"* → `COVID-19`). Metriche: P@10, nDCG@10.

Risultati (in [`eval/results.md`](eval/results.md)):

| Configurazione | Titolo S@1 | Titolo MRR@10 | Parole S@1 | Parole S@10 | Parole MRR@10 | Topical P@10 | Topical nDCG@10 | Latenza media (ms) | p95 (ms) |
|---|---|---|---|---|---|---|---|---|---|
| BM25 + analyzer italiano, title^3 | 0,957 | 0,975 | 0,670 | **0,860** | 0,734 | **0,790** | **0,793** | 0,6 | 1,4 |
| BM25 + analyzer italiano, no boost | 0,873 | 0,907 | **0,690** | **0,860** | **0,745** | 0,780 | 0,784 | 0,4 | 0,9 |
| BM25 standard (no stemming), title^3 | **0,983** | **0,990** | 0,620 | 0,780 | 0,672 | 0,745 | 0,765 | 0,4 | 0,8 |
| LM Dirichlet + analyzer italiano, title^3 | 0,837 | 0,874 | 0,640 | 0,847 | 0,707 | 0,765 | 0,746 | 0,3 | 0,7 |

Cosa emerge:
- L'analyzer italiano migliora le **query tematiche** (P@10 da 0,745 a 0,790) e la ricerca per parole del testo
  (S@10 da 0,780 a 0,860), perché stemming ed elisione fanno corrispondere le forme flesse.
- Sulle **query-titolo** la corrispondenza esatta senza stemming è leggermente migliore, probabilmente perché lo stemming
  rende ambigui titoli molto simili, come le giornate di campionato.
- Il **boost del titolo** aiuta molto la ricerca per titolo (S@1 da 0,873 a 0,957) e costa poco sulle query di contenuto.
- Su questo corpus **BM25 supera LM Dirichlet**.
- Le query rispondono in meno di un millisecondo e mezzo, anche al 95° percentile.

## Struttura del progetto

```
src/main/java/it/uniroma3/idd/wikinews/
  Main.java              riga di comando: index, serve, eval
  TextExtractor.java     testo da HTML e date italiane
  Analyzers.java         analyzer italiano, analyzer "plain", PerFieldAnalyzerWrapper
  WikinewsIndexer.java   lettura in streaming del dump e IndexWriter
  WikinewsSearcher.java  query, ranking, filtri, paginazione, snippet
  WebServer.java         pagina web e API JSON (com.sun.net.httpserver)
  Evaluator.java         valutazione sperimentale
src/main/resources/static/index.html   interfaccia web (HTML + JS, senza build)
src/test/java/...       test JUnit 5 e mini-dump sintetico
eval/results.md          risultati dell'ultima valutazione
```

## Riferimenti

- Slide del corso *Ingegneria dei Dati*: "Lucene & Elasticsearch" (2025)
- [tteofili/lucenex](https://github.com/tteofili/lucenex): esempi d'uso di Lucene
- [Apache Lucene 10 — documentazione](https://lucene.apache.org/core/10_0_0/)
