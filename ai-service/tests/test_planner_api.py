"""Meal-plan generation and grocery endpoints against a real pgvector database (FR-015–FR-017, FR-011/012, QA-011,
QA-012, QA-026). Synthetic fixture recipes; a constant embedder makes similarity equal for every recipe so the
expiry and coverage rules alone decide the order."""

import psycopg
import pytest
from fastapi.testclient import TestClient

from app.importer import import_recipes
from app.main import app, get_embed
from app.sources import parse_mealdb

UNIT = [1.0] + [0.0] * 383


def constant_embed(texts):
    return [UNIT for _ in texts]


def meal(meal_id, title, category, ingredients):
    record = {"idMeal": meal_id, "strMeal": title, "strCategory": category, "strArea": "Indian",
              "strInstructions": "Synthetic fixture.", "strSource": ""}
    for n, (name, measure) in enumerate(ingredients, start=1):
        record[f"strIngredient{n}"] = name
        record[f"strMeasure{n}"] = measure
    return parse_mealdb(record)[0]


FIXTURES = [
    meal("p1", "Breakfast Banana Oats", "Breakfast", [("Oats", "100g"), ("Banana", "2")]),
    meal("p2", "Breakfast Masala Eggs", "Breakfast", [("Egg", "3"), ("Onion", "1")]),
    meal("p3", "Breakfast Poha Bowl", "Breakfast", [("Rice", "200g"), ("Peas", "50g")]),
    meal("m1", "Carrot Stew", "Vegetarian", [("Carrot", "300g"), ("Lentils", "200g")]),
    meal("m2", "Carrot Soup", "Vegetarian", [("Carrot", "500g"), ("Celery", "2 stalks")]),
    meal("m3", "Potato Curry", "Vegetarian", [("Potato", "4"), ("Peas", "100g")]),
    meal("m4", "Lentil Dal", "Vegetarian", [("Lentils", "250g"), ("Turmeric", "1 tsp")]),
    meal("d1", "Fixture Dessert", "Dessert", [("Sugar", "100g"), ("Milk", "1l")]),
]


@pytest.fixture(scope="module")
def client(database, service_env_module):
    with psycopg.connect(database["owner"]) as conn:
        import_recipes(conn, FIXTURES, constant_embed)
    app.dependency_overrides[get_embed] = lambda: constant_embed
    yield TestClient(app), service_env_module
    app.dependency_overrides.clear()


@pytest.fixture(scope="module")
def service_env_module(database):
    import os
    import secrets

    token = secrets.token_urlsafe(32)
    old = {k: os.environ.get(k) for k in ("SERVICE_TO_SERVICE_SECRET", "AI_DATABASE_URL")}
    os.environ["SERVICE_TO_SERVICE_SECRET"] = token
    os.environ["AI_DATABASE_URL"] = database["reader"]
    yield {"X-Service-Token": token}
    for key, value in old.items():
        if value is None:
            os.environ.pop(key, None)
        else:
            os.environ[key] = value


def plan(client, pantry, diets=(), allergens=()):
    http, headers = client
    response = http.post("/internal/v1/meal-plans/generate", headers=headers,
                         json={"pantry": pantry, "diets": list(diets), "allergens": list(allergens)})
    assert response.status_code == 200, response.text
    return response.json()


def test_plan_has_21_slots_in_order_with_courses_respected_and_no_repeats(client):
    result = plan(client, [{"name": "Carrot"}])
    entries = result["entries"]
    assert len(entries) == 21
    assert [(e["day"], e["mealPeriod"]) for e in entries[:4]] == [(0, "breakfast"), (0, "lunch"), (0, "dinner"),
                                                                  (1, "breakfast")]
    planned = [e for e in entries if e["recipeId"]]
    assert len({e["recipeId"] for e in planned}) == len(planned)
    breakfasts = {e["title"] for e in planned if e["mealPeriod"] == "breakfast"}
    assert breakfasts <= {"Breakfast Banana Oats", "Breakfast Masala Eggs", "Breakfast Poha Bowl"}
    assert all(e["title"] != "Fixture Dessert" for e in planned)
    unavailable = [e for e in entries if not e["recipeId"]]
    assert len(unavailable) == 21 - 3 - 4
    assert all(e["reason"].startswith("Not enough different") for e in unavailable)


def test_expiring_items_are_used_first_and_stop_boosting_once_used(client):
    pantry = [{"name": "Carrot", "daysToExpiry": 0}, {"name": "Potato", "daysToExpiry": 0}, {"name": "Lentils"}]
    entries = plan(client, pantry)["entries"]
    assert entries[1]["title"] == "Carrot Stew"          # covers 2/2 and uses expiring carrot
    assert entries[2]["title"] == "Potato Curry"         # carrot already used; potato still expiring
    assert entries[1]["usesExpiringItems"] == ["carrot"]


def test_preferences_filter_every_slot_and_explain_empty_ones(client):
    result = plan(client, [{"name": "Rice"}], allergens=["eggs"])
    titles = {e.get("title") for e in result["entries"]}
    assert "Breakfast Masala Eggs" not in titles
    vegan = plan(client, [{"name": "Rice"}], diets=["vegan"])
    assert all(e["recipeId"] is None for e in vegan["entries"])
    assert vegan["entries"][0]["reason"] == "No breakfast recipe fits your diet and allergen settings."
    assert vegan["excludedByPreferences"] == 7


def test_plan_is_deterministic_and_includes_a_grocery_list(client):
    pantry = [{"name": "Carrot", "daysToExpiry": 1, "quantity": 1, "unit": "kg"},
              {"name": "Lentils", "quantity": 100, "unit": "g"}]
    first, second = plan(client, pantry), plan(client, pantry)
    assert first == second
    grocery = {i["ingredient"]: i for i in first["grocery"]["items"]}
    assert grocery["lentil"]["quantity"] == 350.0 and grocery["lentil"]["unit"] == "g"   # 200 + 250 - 100
    assert "carrot" in first["grocery"]["coveredByPantry"]                              # 800 g <= 1 kg
    assert grocery["celery"]["unresolved"] is True                                       # "2 stalks"


def test_grocery_recalculation_endpoint(client, database):
    http, headers = client
    with psycopg.connect(database["owner"]) as conn:
        ids = [str(r[0]) for r in conn.execute("SELECT id FROM recipes WHERE source_key IN ('m1', 'm4')")]
    response = http.post("/internal/v1/grocery/compute", headers=headers,
                         json={"recipeIds": ids, "pantry": [{"name": "Lentils", "quantity": 1, "unit": "kg"}]})
    assert response.status_code == 200
    body = response.json()
    assert "lentil" in body["coveredByPantry"]
    assert [i["ingredient"] for i in body["items"]] == ["carrot", "turmeric"]
    assert http.post("/internal/v1/grocery/compute", json={"recipeIds": [], "pantry": []}).status_code == 401
    bad = http.post("/internal/v1/grocery/compute", headers=headers,
                    json={"recipeIds": ["not-a-uuid"], "pantry": []})
    assert bad.status_code == 400
