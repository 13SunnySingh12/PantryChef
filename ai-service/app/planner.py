"""Seven-day meal plan (FR-015, FR-016, FR-011/012) and its grocery list (FR-017).

Slots are filled in date order: breakfast from recipes whose source course is breakfast, lunch and dinner from
mains. Each slot takes the best remaining eligible recipe by the retrieval score (D-032); a recipe is used once per
plan. When a meal uses an expiring pantry item, that item stops boosting later picks, so early slots use up what is
about to expire instead of every meal chasing the same item. A slot with no suitable recipe is returned as
unavailable with a reason; nothing is invented. Deterministic: same inputs, same plan.
"""

from collections.abc import Sequence

import psycopg

from app import eligibility
from app.grocery import Stock, compute
from app.ingredients import matches
from app.retrieval import WEIGHTS, PantryItem, similarities, usable_keys

PERIODS = ("breakfast", "lunch", "dinner")
COURSE_FOR_PERIOD = {"breakfast": "breakfast", "lunch": "main", "dinner": "main"}
GENERATION_VERSION = "planner-1"


def generate(conn: psycopg.Connection, embed, pantry: Sequence[PantryItem], stock: Sequence[Stock],
             diets: Sequence[str], allergens: Sequence[str], days: int = 7) -> dict:
    usable = usable_keys(pantry)
    keys = sorted(usable)
    with conn.cursor() as cur:
        similarity = similarities(cur, embed, keys) if keys else {}
        rows = cur.execute(
            "SELECT r.id, r.title, r.course, r.servings, r.diet_labels, r.allergen_metadata_complete,"
            " array_agg(i.normalized_ingredient ORDER BY i.sort_order),"
            " array(SELECT DISTINCT f FROM recipe_ingredients x, unnest(x.allergen_flags) f WHERE x.recipe_id = r.id)"
            " FROM recipes r JOIN recipe_ingredients i ON i.recipe_id = r.id"
            " WHERE r.course IN ('breakfast', 'main') GROUP BY r.id").fetchall()

    pools: dict[str, list[dict]] = {"breakfast": [], "main": []}
    available = {"breakfast": 0, "main": 0}
    excluded = 0
    for recipe_id, title, course, servings, labels, complete, ingredient_keys, flags in rows:
        available[course] += 1
        if eligibility.reasons(labels, complete, flags or [], diets, allergens):
            excluded += 1
            continue
        hits = {k for k in keys for ingredient in ingredient_keys if matches(k, ingredient)}
        coverage = sum(1 for ingredient in ingredient_keys if any(matches(k, ingredient) for k in keys)) \
            / len(ingredient_keys)
        pools[course].append({"id": recipe_id, "title": title, "servings": servings, "hits": hits,
                              "base": WEIGHTS["coverage"] * coverage
                                      + WEIGHTS["similarity"] * max(0.0, float(similarity.get(recipe_id, 0.0)))})

    used_recipes, spent_expiring, entries = set(), set(), []
    for day in range(days):
        for period in PERIODS:
            course = COURSE_FOR_PERIOD[period]
            best, best_score = None, None
            for candidate in pools[course]:
                if candidate["id"] in used_recipes:
                    continue
                boost = min(1.0, sum(usable[k] for k in candidate["hits"] if k not in spent_expiring))
                score = round(candidate["base"] + WEIGHTS["expiry"] * boost, 6)
                if best is None or (-score, candidate["title"].lower(), str(candidate["id"])) < (
                        -best_score, best["title"].lower(), str(best["id"])):
                    best, best_score = candidate, score
            if best is None:
                entries.append({"day": day, "mealPeriod": period, "recipeId": None,
                                "reason": _reason(course, available[course], len(pools[course]))})
                continue
            used_recipes.add(best["id"])
            spent_expiring |= {k for k in best["hits"] if usable[k] > 0}
            entries.append({"day": day, "mealPeriod": period, "recipeId": str(best["id"]), "title": best["title"],
                            "servings": float(best["servings"]) if best["servings"] is not None else None,
                            "usesExpiringItems": sorted(k for k in best["hits"] if usable[k] > 0)})
    return {"entries": entries, "excludedByPreferences": excluded, "generationVersion": GENERATION_VERSION,
            "grocery": grocery_for(conn, [e["recipeId"] for e in entries if e["recipeId"]], stock)}


def grocery_for(conn: psycopg.Connection, recipe_ids: Sequence[str], stock: Sequence[Stock]) -> dict:
    if not recipe_ids:
        return compute([], stock)
    with conn.cursor() as cur:
        needs = cur.execute("SELECT normalized_ingredient, quantity, unit FROM recipe_ingredients"
                            " WHERE recipe_id = ANY(%s::uuid[])", (list(recipe_ids),)).fetchall()
    return compute(needs, stock)


def _reason(course: str, in_database: int, eligible: int) -> str:
    label = "breakfast" if course == "breakfast" else "main-course"
    if in_database == 0:
        return f"No {label} recipes are available yet."
    if eligible == 0:
        return f"No {label} recipe fits your diet and allergen settings."
    return f"Not enough different {label} recipes fit your settings to fill every slot."
