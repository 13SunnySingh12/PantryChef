"""Local sentence-transformers embeddings (B-02: all-MiniLM-L6-v2, Apache-2.0, 384-d). No paid API."""

from collections.abc import Callable, Sequence
from functools import lru_cache

from app.config import EMBEDDING_DIMENSIONS, EMBEDDING_MODEL_ID

Embed = Callable[[Sequence[str]], list[list[float]]]


@lru_cache(maxsize=1)
def _model():
    from sentence_transformers import SentenceTransformer  # heavy import, only when embeddings are needed

    model = SentenceTransformer(EMBEDDING_MODEL_ID, device="cpu")
    dimensions = model.get_embedding_dimension()
    if dimensions != EMBEDDING_DIMENSIONS:
        raise RuntimeError(f"{EMBEDDING_MODEL_ID} returned {dimensions} dimensions, schema expects {EMBEDDING_DIMENSIONS}")
    return model


def embed(texts: Sequence[str]) -> list[list[float]]:
    """Unit-length vectors, so cosine distance in pgvector equals 1 - dot product."""
    return _model().encode(list(texts), normalize_embeddings=True, batch_size=64).tolist()


def chunk_text(recipe: dict) -> str:
    """What gets embedded for a recipe: its title and normalized ingredient names (short enough for 256 tokens)."""
    names = ", ".join(dict.fromkeys(i["normalized"] for i in recipe["ingredients"]))
    return f"{recipe['title']}. Ingredients: {names}."[:1000]


def query_text(pantry_keys: Sequence[str]) -> str:
    return "Ingredients: " + ", ".join(pantry_keys) + "."
