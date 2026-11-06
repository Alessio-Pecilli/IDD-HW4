"""Integration tests: synthetic dump indexed into a throwaway Elasticsearch index, then queried."""


def ids(res):
    return [int(r["id"]) for r in res["results"]]


def test_indexing_stats(index):
    assert index["empty"] == 1                 # page with no text is skipped
    assert index["older_revisions"] == 1       # stale revision rejected by external versioning
    assert index["documents"] == 5


def test_index_keeps_newest_revision(search):
    assert ids(search("pescara")) == [1]       # newer revision of doc 1 replaced the old one
    assert search("bozza")["total"] == 0       # older revision of doc 3 was ignored
    assert search("juventus")["results"][0]["title"] == "La Juventus vince il campionato"


def test_stemming_finds_inflected_forms(search):
    # "terremoto" must also find doc 4, which only contains "terremoti"
    assert set(ids(search("terremoto"))) == {1, 4}
    assert set(ids(search("terremoto", model="plain"))) == {1}
    assert set(ids(search("elezioni"))) == {2, 5}


def test_accent_and_elision(search):
    assert ids(search("citta")) == [3]
    assert ids(search("aquila")) == [1]        # "L'Aquila": elision filter strips the article


def test_phrase_query(search):
    assert ids(search('"presidente della repubblica"')) == [5]


def test_and_semantics(search):
    assert ids(search("presidente obama")) == [2]


def test_or_and_not(search):
    assert set(ids(search("juventus | obama"))) == {2, 3}
    assert ids(search("terremoto -giappone")) == [1]


def test_prefix_query(search):
    assert set(ids(search("terrem*"))) == {1, 4}


def test_title_boost(search):
    # both docs mention "presidente" in the body; only doc 5 has it in the title
    assert ids(search("presidente"))[0] == 5


def test_lmdirichlet_model(search):
    assert set(ids(search("terremoto", model="lmdirichlet"))) == {1, 4}


def test_date_filter_and_sort(search):
    assert ids(search("terremoto", date_from="2010-01-01")) == [4]
    assert ids(search("presidente | terremoto", sort="date")) == [4, 1, 2, 5]


def test_pagination(search):
    q = "presidente | terremoto | juventus"
    pages = [search(q, size=2, page=p) for p in (1, 2, 3)]
    assert pages[0]["total"] == 5
    assert len({i for p in pages for i in ids(p)}) == 5


def test_no_results_and_empty_query(search):
    assert search("parolainesistente")["total"] == 0
    assert search("   ")["total"] == 0


def test_result_fields_and_highlight(search):
    r = search("scudetto")["results"][0]
    assert r["url"].startswith("https://it.wikinews.org/wiki/")
    assert r["date"] == "2012-05-20" and r["score"] > 0
    assert "<mark>scudetto</mark>" in r["snippet"]
