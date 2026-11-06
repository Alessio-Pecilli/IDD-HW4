"""End-to-end tests of the HTTP layer (FastAPI TestClient)."""


def test_home_page(client):
    r = client.get("/")
    assert r.status_code == 200 and "Wikinews IT Search" in r.text


def test_stats(client):
    s = client.get("/api/stats").json()
    assert (s["documents"], s["first_date"], s["last_date"]) == (5, "2006-05-10", "2012-05-20")
    assert s["lucene_version"].startswith("10.")


def test_search_endpoint(client):
    data = client.get("/api/search", params={"q": "terremoto"}).json()
    assert data["total"] == 2 and len(data["results"]) == 2


def test_search_endpoint_options(client):
    data = client.get("/api/search", params={"q": "terremoto", "model": "plain", "date_from": "2009-01-01",
                                             "date_to": "2009-12-31", "sort": "date"}).json()
    assert [r["id"] for r in data["results"]] == ["1"]


def test_input_validation(client):
    assert client.get("/api/search").status_code == 422                                   # q missing
    assert client.get("/api/search", params={"q": "x", "page": 0}).status_code == 422
    assert client.get("/api/search", params={"q": "x", "sort": "drop"}).status_code == 422
    assert client.get("/api/search", params={"q": "x", "model": "tfidf"}).status_code == 422
    assert client.get("/api/search", params={"q": "x", "date_from": "ieri"}).status_code == 422
    assert client.get("/api/search", params={"q": "x" * 501}).status_code == 422


def test_hostile_queries_do_not_crash(client):
    for q in ['"', "a AND", "NEAR(a b)", "title:x", "*", "a)(", "'; DROP TABLE docs; --", "<script>alert(1)</script>", "a\\", "~2", "((("]:
        r = client.get("/api/search", params={"q": q})
        assert r.status_code == 200, q
    assert client.get("/api/stats").json()["documents"] == 5


def test_snippet_is_html_escaped(client):
    r = client.get("/api/search", params={"q": "script"}).json()
    assert all("<script" not in d["snippet"] for d in r["results"])
