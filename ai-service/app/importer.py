"""Idempotent recipe import + indexing (FR-010, QA-025).

Recipes are upserted on (source_name, source_key); their ingredients are replaced; a chunk is re-embedded only when
its text changed. Re-running the same input changes nothing. Runs as the owner role (INGEST_DATABASE_URL).
"""

from collections import Counter

import psycopg

from app.config import EMBEDDING_MODEL_ID
from app.db import vector_literal
from app.embeddings import Embed, chunk_text

UPSERT_RECIPE = """
INSERT INTO recipes (source_name, source_key, source_url, source_license, attribution, dataset_version, title,
                     instructions, cuisine, servings, diet_labels, allergen_metadata_complete, allergen_source)
VALUES (%(source_name)s, %(source_key)s, %(source_url)s, %(source_license)s, %(attribution)s, %(dataset_version)s,
        %(title)s, %(instructions)s, %(cuisine)s, %(servings)s, %(diet_labels)s, %(allergen_metadata_complete)s,
        %(allergen_source)s)
ON CONFLICT (source_name, source_key) DO UPDATE SET
  source_url = EXCLUDED.source_url, source_license = EXCLUDED.source_license, attribution = EXCLUDED.attribution,
  dataset_version = EXCLUDED.dataset_version, title = EXCLUDED.title, instructions = EXCLUDED.instructions,
  cuisine = EXCLUDED.cuisine, servings = EXCLUDED.servings, diet_labels = EXCLUDED.diet_labels,
  allergen_metadata_complete = EXCLUDED.allergen_metadata_complete, allergen_source = EXCLUDED.allergen_source,
  updated_at = now()
RETURNING id
"""


def import_recipes(conn: psycopg.Connection, recipes: list[dict], embed: Embed, batch: int = 128) -> dict:
    """Upsert recipes and their single embedding chunk. Returns counts for the import report."""
    stats = Counter()
    pending = []  # (recipe_id, text, dataset_version) needing a new embedding
    with conn.cursor() as cur:
        for recipe in recipes:
            recipe_id = cur.execute(UPSERT_RECIPE, recipe).fetchone()[0]
            cur.execute("DELETE FROM recipe_ingredients WHERE recipe_id = %s", (recipe_id,))
            cur.executemany(
                "INSERT INTO recipe_ingredients (recipe_id, ingredient_name, normalized_ingredient, quantity, unit,"
                " allergen_flags, sort_order) VALUES (%s, %s, %s, %s, %s, %s, %s)",
                [(recipe_id, i["name"][:500], i["normalized"], i["quantity"] if i["quantity"] and i["quantity"] > 0
                  else None, i["unit"] if i["quantity"] else None, i["allergens"], n)
                 for n, i in enumerate(recipe["ingredients"])])
            text = chunk_text(recipe)
            existing = cur.execute(
                "SELECT chunk_text FROM recipe_chunks WHERE recipe_id = %s AND chunk_index = 0 AND embedding_model = %s",
                (recipe_id, EMBEDDING_MODEL_ID)).fetchone()
            if existing and existing[0] == text:
                stats["chunks_unchanged"] += 1
            else:
                pending.append((recipe_id, text, recipe["dataset_version"]))
            stats["recipes"] += 1
        for start in range(0, len(pending), batch):
            part = pending[start:start + batch]
            vectors = embed([text for _, text, _ in part])
            cur.executemany(
                "INSERT INTO recipe_chunks (recipe_id, chunk_index, chunk_text, embedding, embedding_model,"
                " dataset_version) VALUES (%s, 0, %s, %s::vector, %s, %s)"
                " ON CONFLICT (recipe_id, chunk_index, embedding_model) DO UPDATE SET chunk_text = EXCLUDED.chunk_text,"
                " embedding = EXCLUDED.embedding, dataset_version = EXCLUDED.dataset_version",
                [(rid, text, vector_literal(vec), EMBEDDING_MODEL_ID, version)
                 for (rid, text, version), vec in zip(part, vectors, strict=True)])
            stats["chunks_embedded"] += len(part)
    return dict(stats)
