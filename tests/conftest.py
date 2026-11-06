import io
import json
import tarfile

import pytest
from fastapi.testclient import TestClient

from wikinews_search import es as es_mod
from wikinews_search.app import create_app
from wikinews_search.indexer import build_index

TEST_INDEX = "wikinews_test"


def record(i, title, date, paragraphs, categories=()):
    body = "".join(f"<p>{p}</p>" for p in paragraphs)
    html = f"<html><head><style>.x{{}}</style></head><body><section><p><b>{date}</b></p>{body}</section></body></html>"
    return {
        "identifier": i,
        "name": title,
        "url": f"https://it.wikinews.org/wiki/{title.replace(' ', '_')}",
        "categories": [{"name": f"Categoria:{c}"} for c in categories],
        "article_body": {"html": html, "wikitext": f"{{{{Data|{date}}}}}\n..."},
    }


DOCS = [
    record(1, "Terremoto in Abruzzo", "6 aprile 2009",
           ["Un forte terremoto ha colpito L'Aquila nella notte.", "Le scosse sono state avvertite a Roma."],
           ["Disastri e incidenti", "Italia"]),
    record(2, "Elezioni presidenziali negli Stati Uniti", "4 novembre 2008",
           ["Barack Obama vince le elezioni e diventa presidente.", "Grande festa a Chicago."],
           ["Politica", "Stati Uniti d'America"]),
    record(3, "La Juventus vince il campionato", "20 maggio 2012",
           ["La squadra torinese conquista lo scudetto di calcio.", "Festa per i tifosi della città."],
           ["Sport", "Calcio"]),
    record(4, "Nuove scosse di terremoti in Giappone", "11 marzo 2011",
           ["Diversi terremoti hanno colpito la costa. Allarme tsunami.", "Tokyo ha sentito la scossa."],
           ["Disastri e incidenti", "Asia"]),
    record(5, "Elezione del presidente della Repubblica", "10 maggio 2006",
           ["Il parlamento ha eletto il nuovo presidente della Repubblica italiana.", "Napolitano al Quirinale."],
           ["Politica", "Italia"]),
    record(6, "Pagina vuota", "1 gennaio 2010", []),
    # older revision of doc 3 appearing later in the dump: must not replace the newer one
    record(3, "La Juventus vince il campionato (vecchia)", "20 maggio 2012", ["Bozza."], ["Sport"]) | {"version": {"identifier": 1}},
    # newer revision of doc 1: must replace it
    record(1, "Terremoto in Abruzzo", "6 aprile 2009",
           ["Un forte terremoto ha colpito L'Aquila nella notte.", "Le scosse sono state avvertite a Roma e Pescara."],
           ["Disastri e incidenti", "Italia"]) | {"version": {"identifier": 99}},
]
DOCS[5]["article_body"]["html"] = ""  # no text -> must be skipped
for d in DOCS[:5]:
    d["version"] = {"identifier": 10}


@pytest.fixture(scope="session")
def dump_path(tmp_path_factory):
    path = tmp_path_factory.mktemp("dump") / "test.json.tar.gz"
    data = "\n".join(json.dumps(d) for d in DOCS).encode()
    with tarfile.open(path, "w:gz") as tar:
        info = tarfile.TarInfo("itwikinews_namespace_0_0.ndjson")
        info.size = len(data)
        tar.addfile(info, io.BytesIO(data))
    return path


@pytest.fixture(scope="session")
def es():
    client = es_mod.client()
    if not client.ping():
        pytest.skip(f"Elasticsearch not reachable at {es_mod.ES_URL} (docker compose up -d)")
    return client


@pytest.fixture(scope="session")
def index(es, dump_path):
    stats = build_index(str(dump_path), es, TEST_INDEX)
    yield stats
    es.indices.delete(index=TEST_INDEX, ignore_unavailable=True)


@pytest.fixture
def search(es, index):
    return lambda q, **kw: es_mod.search(es, q, index=TEST_INDEX, **kw)


@pytest.fixture
def client(es, index):
    return TestClient(create_app(es, TEST_INDEX))
