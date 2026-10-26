"""Retrieval endpoint against a real pgvector database (FR-009, FR-010, FR-011, FR-012, FR-016; QA-009, QA-010,
QA-017, QA-022, QA-025, QA-026). Recipes are synthetic fixtures built through the TheMealDB parser."""

import psycopg
import pytest
from fastapi.testclient import TestClient

from app.importer import import_recipes
from app.main import app, get_embed
from app.sources import parse_mealdb
from tests.conftest import fake_embed

URL = "/internal/v1/recipes/retrieve"


def meal(meal_id, title, category, ingredients):
    record = {"idMeal": meal_id, "strMeal": title, "strCategory": category, "strArea": "Indian",
              "strInstructions": "Synthetic fixture.", "strSource": ""}
    for n, name in enumerate(ingredients, start=1):
        record[f"strIngredient{n}"] = name
        record[f"strMeasure{n}"] = "100g"
    return parse_mealdb(record)[0]


FIXTURES = [
    meal("f1", "Fixture Palak Paneer", "Vegetarian", ["Spinach", "Paneer", "Onion", "Garlic"]),
    meal("f2", "Fixture Spinach Rice", "Vegan", ["Spinach", "Rice", "Onion", "Garlic"]),
    meal("f3", "Fixture Tomato Rice", "Vegan", ["Tomato", "Rice", "Onion", "Garlic"]),
    meal("f4", "Fixture Pesto Pasta", "Vegetarian", ["Pesto", "Pasta"]),          # allergen data unknown
    meal("f5", "Fixture Chicken Curry", "Chicken", ["Chicken", "Onion", "Tomato", "Garlic"]),
]


@pytest.fixture(scope="module")
def loaded(database):
    with psycopg.connect(database["owner"]) as conn:
        first = import_recipes(conn, FIXTURES, fake_embed)
    return first


@pytest.fixture
def client(service_env, loaded):
    app.dependency_overrides[get_embed] = lambda: fake_embed
    yield TestClient(app), service_env
    app.dependency_overrides.clear()


def ask(client, pantry, diets=(), allergens=(), limit=10):
    http, headers = client
    return http.post(URL, headers=headers, json={"pantry": pantry, "diets": list(diets),
                                                 "allergens": list(allergens), "limit": limit})


def titles(response):
    return [r["title"] for r in response.json()["results"]]


def test_results_cite_stored_recipes_with_matched_and_missing(client, database):
    response = ask(client, [{"name": "Spinach", "daysToExpiry": 5}, {"name": "Basmati rice"}])
    assert response.status_code == 200
    first = response.json()["results"][0]
    assert first["title"] == "Fixture Spinach Rice"
    assert first["matchedIngredients"] == ["100g Spinach", "100g Rice"]
    assert first["missingIngredients"] == ["100g Onion", "100g Garlic"]
    assert first["source"]["name"] == "TheMealDB" and first["source"]["url"].endswith("/meal/f2")
    assert "TheMealDB" in first["source"]["attribution"]
    with psycopg.connect(database["owner"]) as conn:
        stored = conn.execute("SELECT title FROM recipes WHERE id = %s", (first["recipeId"],)).fetchone()
    assert stored == ("Fixture Spinach Rice",)


def test_near_expiry_stock_ranks_an_otherwise_equal_recipe_higher(client):
    # Spinach Rice and Tomato Rice both match 3 of 4 ingredients. Only tomato expires soon, so Tomato Rice must
    # win even though the alphabetical tie-break would put Spinach Rice first.
    pantry = [{"name": "spinach", "daysToExpiry": 30}, {"name": "tomato", "daysToExpiry": 1},
              {"name": "rice"}, {"name": "onion"}]
    results = {r["title"]: r for r in ask(client, pantry).json()["results"]}
    order = list(results)
    tomato, spinach = results["Fixture Tomato Rice"], results["Fixture Spinach Rice"]
    assert order.index("Fixture Tomato Rice") < order.index("Fixture Spinach Rice")
    assert tomato["ranking"]["coverage"] == spinach["ranking"]["coverage"] == 0.75
    assert tomato["ranking"]["score"] > spinach["ranking"]["score"]
    assert tomato["usesExpiringItems"] == ["tomato"] and tomato["ranking"]["expiryBoost"] == 0.75
    assert spinach["usesExpiringItems"] == [] and spinach["ranking"]["expiryBoost"] == 0.0


def test_expired_items_are_not_offered_as_stock(client):
    response = ask(client, [{"name": "spinach", "daysToExpiry": -1}])
    assert response.json() == {"results": [], "excludedByPreferences": 0, "reason": "no_usable_pantry_items"}


def test_allergen_exclusion_removes_unsafe_and_unknown_recipes(client):
    pantry = [{"name": "spinach"}, {"name": "pasta"}, {"name": "pesto"}, {"name": "rice"}]
    everything = titles(ask(client, pantry))
    assert {"Fixture Palak Paneer", "Fixture Pesto Pasta"} <= set(everything)
    dairy_free = ask(client, pantry, allergens=["dairy"])
    assert "Fixture Palak Paneer" not in titles(dairy_free)
    assert "Fixture Pesto Pasta" not in titles(dairy_free)  # unknown allergen data is never "safe"
    assert dairy_free.json()["excludedByPreferences"] >= 2


def test_diet_filter_and_no_safe_match_reason(client):
    vegan = ask(client, [{"name": "spinach"}, {"name": "paneer"}], diets=["vegan"])
    assert titles(vegan) == ["Fixture Spinach Rice"]
    nothing = ask(client, [{"name": "chicken"}], diets=["vegetarian"])
    assert nothing.json()["results"] == [] and nothing.json()["reason"] == "no_safe_match"
    unknown = ask(client, [{"name": "dragonfruit"}])
    assert unknown.json()["reason"] == "no_match"


def test_identical_requests_give_identical_results(client):
    pantry = [{"name": "onion", "daysToExpiry": 2}, {"name": "garlic"}, {"name": "rice"}]
    assert ask(client, pantry).json() == ask(client, pantry).json()


def test_internal_routes_require_the_service_token(client, monkeypatch):
    http, headers = client
    assert http.post(URL, json={"pantry": []}).status_code == 401
    assert http.post(URL, headers={"X-Service-Token": "x" * 40}, json={"pantry": []}).status_code == 401
    assert http.get("/internal/v1/health").status_code == 200
    monkeypatch.delenv("SERVICE_TO_SERVICE_SECRET")
    monkeypatch.setattr("app.config._ENV_FILE", __import__("pathlib").Path("does-not-exist"))
    assert http.post(URL, headers=headers, json={"pantry": []}).json()["error"]["code"] == "SERVICE_NOT_CONFIGURED"


@pytest.mark.parametrize("body", [
    {"pantry": [], "limit": 0},
    {"pantry": [], "allergens": ["glitter"]},
    {"pantry": [{"name": "x" * 101}]},
    {"pantry": [], "userId": "someone-else"},
    {"pantry": [{"name": ""}]},
])
def test_invalid_requests_are_rejected(client, body):
    http, headers = client
    response = http.post(URL, headers=headers, json=body)
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "VALIDATION_ERROR"


def test_database_outage_is_a_safe_503(client, monkeypatch):
    monkeypatch.setenv("AI_DATABASE_URL", "postgresql://nobody:wrong@127.0.0.1:1/none")
    response = ask(client, [{"name": "rice"}])
    assert response.status_code == 503
    assert response.json() == {"error": {"code": "DATA_UNAVAILABLE", "message": "The recipe database is unavailable."}}


def test_reimport_is_idempotent(database, loaded):
    with psycopg.connect(database["owner"]) as conn:
        again = import_recipes(conn, FIXTURES, fake_embed)
        counts = conn.execute("SELECT (SELECT count(*) FROM recipes WHERE source_key LIKE 'f%'),"
                              " (SELECT count(*) FROM recipe_chunks)").fetchone()
    assert loaded == {"recipes": 5, "chunks_embedded": 5}
    assert again == {"recipes": 5, "chunks_unchanged": 5}
    assert counts == (5, 5)


def test_service_role_cannot_read_user_data(database):
    with psycopg.connect(database["reader"]) as conn:
        assert conn.execute("SELECT count(*) FROM recipes").fetchone()[0] >= 5
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            conn.execute("SELECT count(*) FROM users")


def test_model_failure_is_a_safe_503(client):
    def broken(texts):
        raise RuntimeError("model files missing")

    app.dependency_overrides[get_embed] = lambda: broken
    response = ask(client, [{"name": "rice"}])
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "MODEL_UNAVAILABLE"
