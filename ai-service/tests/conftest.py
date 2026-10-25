import hashlib
import math
import secrets
from pathlib import Path

import psycopg
import pytest

MIGRATIONS = Path(__file__).resolve().parents[2] / "database" / "migrations"


def fake_embed(texts):
    """Deterministic bag-of-words embedding (384-d, unit length) so tests never download a model."""
    vectors = []
    for text in texts:
        vector = [0.0] * 384
        for word in text.lower().replace(",", " ").replace(".", " ").split():
            vector[int(hashlib.sha256(word.encode()).hexdigest(), 16) % 384] += 1.0
        norm = math.sqrt(sum(v * v for v in vector)) or 1.0
        vectors.append([v / norm for v in vector])
    return vectors


@pytest.fixture(scope="session")
def database():
    """Throwaway PostgreSQL 17 + pgvector with the real migrations, plus a login for the read-only AI role."""
    from testcontainers.community.postgres import PostgresContainer

    with PostgresContainer("pgvector/pgvector:pg17", driver=None) as pg:
        owner_url = pg.get_connection_url()
        password = secrets.token_urlsafe(16)
        with psycopg.connect(owner_url, autocommit=True) as conn:
            for migration in sorted(MIGRATIONS.glob("V*__*.sql"), key=lambda p: int(p.name[1:].split("__")[0])):
                conn.execute(migration.read_text(encoding="utf-8"))
            conn.execute(f"ALTER ROLE pantrychef_ai LOGIN PASSWORD '{password}'")
        host, port = pg.get_container_host_ip(), pg.get_exposed_port(5432)
        reader_url = f"postgresql://pantrychef_ai:{password}@{host}:{port}/{pg.dbname}"
        yield {"owner": owner_url, "reader": reader_url}


@pytest.fixture
def service_env(monkeypatch, database):
    token = secrets.token_urlsafe(32)
    monkeypatch.setenv("SERVICE_TO_SERVICE_SECRET", token)
    monkeypatch.setenv("AI_DATABASE_URL", database["reader"])
    return {"X-Service-Token": token}
