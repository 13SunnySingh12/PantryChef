"""Database access. The service connects as the read-only `pantrychef_ai` role (D-010) via AI_DATABASE_URL; the
import scripts use INGEST_DATABASE_URL (an owner role) because they write recipes."""

import psycopg
from fastapi import HTTPException

from app.config import setting


def connect(url_setting: str = "AI_DATABASE_URL") -> psycopg.Connection:
    url = setting(url_setting)
    if not url:
        raise HTTPException(503, detail=("DATA_UNAVAILABLE", "The recipe database is not configured."))
    try:
        # ponytail: one connection per request; add psycopg_pool if request volume ever matters.
        return psycopg.connect(url, connect_timeout=5)
    except psycopg.OperationalError as exc:
        raise HTTPException(503, detail=("DATA_UNAVAILABLE", "The recipe database is unavailable.")) from exc


def vector_literal(values) -> str:
    """pgvector text form, so no extra adapter package is needed."""
    return "[" + ",".join(f"{v:.7f}" for v in values) + "]"
