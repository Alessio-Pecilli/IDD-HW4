"""Web interface + JSON API over Elasticsearch.  Run: uvicorn wikinews_search.app:app"""
from pathlib import Path

from elasticsearch import ApiError, Elasticsearch
from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import FileResponse

from . import es as es_mod

STATIC = Path(__file__).parent / "static"


def create_app(es: Elasticsearch | None = None, index: str = es_mod.INDEX) -> FastAPI:
    es = es or es_mod.client()
    app = FastAPI(title="Wikinews IT search")

    @app.get("/", include_in_schema=False)
    def home():
        return FileResponse(STATIC / "index.html")

    @app.get("/api/search")
    def api_search(q: str = Query(..., max_length=500),
                   page: int = Query(1, ge=1, le=1000),  # from+size must stay below ES max_result_window
                   size: int = Query(10, ge=1, le=10),
                   model: str = Query("italian", pattern="^(italian|plain|lmdirichlet)$"),
                   sort: str = Query("relevance", pattern="^(relevance|date)$"),
                   date_from: str | None = Query(None, pattern=r"^\d{4}-\d{2}-\d{2}$"),
                   date_to: str | None = Query(None, pattern=r"^\d{4}-\d{2}-\d{2}$")):
        try:
            return es_mod.search(es, q, page, size, model, sort, date_from, date_to, index=index)
        except ApiError as e:
            raise HTTPException(502, f"search backend error: {e.message}")

    @app.get("/api/stats")
    def api_stats():
        res = es.search(index=index, size=0, aggs={"first": {"min": {"field": "date"}},
                                                   "last": {"max": {"field": "date"}}})
        return {"documents": es.count(index=index)["count"],  # hits.total is capped at 10k without track_total_hits
                "first_date": res["aggregations"]["first"].get("value_as_string"),
                "last_date": res["aggregations"]["last"].get("value_as_string"),
                "lucene_version": es.info()["version"]["lucene_version"]}

    return app


app = create_app()
