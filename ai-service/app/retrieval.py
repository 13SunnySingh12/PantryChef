"""Pantry-based recipe retrieval with citations (FR-009, FR-010, FR-016).

Candidates come from vector similarity (pgvector) plus exact ingredient overlap. Each candidate is checked against
the user's diets/allergens (fail closed, D-028) BEFORE ranking, then scored:

    score = 0.6 * coverage + 0.3 * expiry_boost + 0.1 * similarity

coverage      = recipe ingredients the pantry has / all recipe ingredients
expiry_boost  = min(1, sum over matched pantry items expiring in 0..3 days of (4 - days) / 4)
similarity    = cosine similarity of the recipe chunk to the pantry query (0 if not a vector candidate)

Ties break on title then id, so identical inputs give identical output. Expired items (days < 0) are not offered
as usable stock. Every result is a stored recipe with its source, license and attribution.
"""

from collections.abc import Sequence
from dataclasses import dataclass

import psycopg

from app import eligibility
from app.config import EMBEDDING_MODEL_ID
from app.db import vector_literal
from app.embeddings import Embed, query_text
from app.ingredients import matches, normalize

VECTOR_CANDIDATES = 200
OVERLAP_CANDIDATES = 500
WEIGHTS = {"coverage": 0.6, "expiry": 0.3, "similarity": 0.1}


@dataclass(frozen=True)
class PantryItem:
    name: str
    days_to_expiry: int | None = None


def _expiry_weight(days: int | None) -> float:
    return (4 - days) / 4 if days is not None and 0 <= days <= 3 else 0.0


def usable_keys(pantry: Sequence[PantryItem]) -> dict[str, float]:
    """Ingredient key -> strongest expiry weight among usable pantry items with that key (expired items excluded)."""
    usable: dict[str, float] = {}
    for item in pantry:
        key = normalize(item.name)
        if key and (item.days_to_expiry is None or item.days_to_expiry >= 0):
            usable[key] = max(usable.get(key, 0.0), _expiry_weight(item.days_to_expiry))
    return usable


def similarities(cur: psycopg.Cursor, embed: Embed, keys: Sequence[str]) -> dict:
    """Recipe id -> cosine similarity for the closest VECTOR_CANDIDATES recipe chunks to the pantry query."""
    query_vector = vector_literal(embed([query_text(keys)])[0])
    return dict(cur.execute(
        "SELECT recipe_id, 1 - (embedding <=> %s::vector) FROM recipe_chunks WHERE embedding_model = %s"
        " ORDER BY embedding <=> %s::vector LIMIT %s",
        (query_vector, EMBEDDING_MODEL_ID, query_vector, VECTOR_CANDIDATES)).fetchall())


def retrieve(conn: psycopg.Connection, embed: Embed, pantry: Sequence[PantryItem], diets: Sequence[str],
             allergens: Sequence[str], limit: int = 10) -> dict:
    usable = usable_keys(pantry)
    if not usable:
        return {"results": [], "excludedByPreferences": 0, "reason": "no_usable_pantry_items"}
    keys = sorted(usable)

    with conn.cursor() as cur:
        similarity = similarities(cur, embed, keys)
        suffixes = sorted({" ".join(k.split()[i:]) for k in keys for i in range(len(k.split()))})
        overlap = [row[0] for row in cur.execute(
            "SELECT DISTINCT recipe_id FROM recipe_ingredients WHERE normalized_ingredient = ANY(%s)"
            " OR normalized_ingredient LIKE ANY(%s) LIMIT %s",
            (suffixes, ["% " + k for k in keys], OVERLAP_CANDIDATES)).fetchall()]
        ids = list(dict.fromkeys([*similarity, *overlap]))
        if not ids:
            return {"results": [], "excludedByPreferences": 0, "reason": "no_match"}
        recipes = {row[0]: row for row in cur.execute(
            "SELECT id, title, source_name, source_url, source_license, attribution, cuisine, diet_labels,"
            " allergen_metadata_complete, servings FROM recipes WHERE id = ANY(%s)", (ids,)).fetchall()}
        ingredients = {}
        for recipe_id, name, key, flags in cur.execute(
                "SELECT recipe_id, ingredient_name, normalized_ingredient, allergen_flags FROM recipe_ingredients"
                " WHERE recipe_id = ANY(%s) ORDER BY recipe_id, sort_order", (ids,)).fetchall():
            ingredients.setdefault(recipe_id, []).append((name, key, flags))

    results, excluded = [], 0
    for recipe_id, (_, title, source, url, license_, attribution, cuisine, labels, complete, servings) in recipes.items():
        items = ingredients.get(recipe_id, [])
        flags = {flag for _, _, f in items for flag in f}
        if eligibility.reasons(labels, complete, flags, diets, allergens):
            excluded += 1
            continue
        matched, missing, used = [], [], set()
        for name, key, _ in items:
            pantry_hit = next((k for k in keys if matches(k, key)), None)
            if pantry_hit:
                matched.append(name)
                used.add(pantry_hit)
            else:
                missing.append(name)
        if not matched:
            continue
        coverage = len(matched) / len(items)
        boost = min(1.0, sum(usable[k] for k in used))
        sim = max(0.0, float(similarity.get(recipe_id, 0.0)))
        score = WEIGHTS["coverage"] * coverage + WEIGHTS["expiry"] * boost + WEIGHTS["similarity"] * sim
        results.append({
            "recipeId": str(recipe_id),
            "title": title,
            "source": {"name": source, "url": url, "license": license_, "attribution": attribution},
            "cuisine": cuisine,
            "servings": float(servings) if servings is not None else None,
            "dietLabels": list(labels),
            "allergenDataComplete": complete,
            "matchedIngredients": matched,
            "missingIngredients": missing,
            "usesExpiringItems": sorted(k for k in used if usable[k] > 0),
            "ranking": {"score": round(score, 4), "coverage": round(coverage, 4), "expiryBoost": round(boost, 4),
                        "similarity": round(sim, 4)},
        })
    results.sort(key=lambda r: (-r["ranking"]["score"], r["title"].lower(), r["recipeId"]))
    reason = None if results else ("no_safe_match" if excluded else "no_match")
    return {"results": results[:limit], "excludedByPreferences": excluded, "reason": reason}
