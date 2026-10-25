"""Settings from environment variables, falling back to the repo-root .env (never committed)."""

import os
from pathlib import Path

EMBEDDING_MODEL_ID = "sentence-transformers/all-MiniLM-L6-v2"
EMBEDDING_DIMENSIONS = 384

_ENV_FILE = Path(__file__).resolve().parents[2] / ".env"


def _dotenv() -> dict[str, str]:
    if not _ENV_FILE.is_file():
        return {}
    pairs = {}
    for line in _ENV_FILE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            pairs[key.strip()] = value.strip()
    return pairs


def setting(name: str) -> str | None:
    """Real environment first, then .env. Read on each call so tests can change it."""
    return os.environ.get(name) or _dotenv().get(name)
