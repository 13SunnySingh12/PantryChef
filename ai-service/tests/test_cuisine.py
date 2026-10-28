"""Cuisine classifier code paths (FR-013, QA-015) on synthetic recipes. Synthetic data tests the code, not accuracy;
real metrics are in model_cards/cuisine.json."""

import json
import random

import pytest
from fastapi.testclient import TestClient

from app import cuisine
from app.main import app

SIGNATURES = {
    "Indian": ["garam masala", "turmeric", "ghee", "cumin", "lentil"],
    "Italian": ["basil", "parmesan", "olive oil", "pasta", "oregano"],
    "Mexican": ["tortilla", "black bean", "jalapeno", "lime", "cilantro"],
}
SHARED = ["onion", "garlic", "salt", "tomato", "rice", "water", "pepper"]


def recipe(label, keys):
    return {"cuisine": label, "ingredients": [{"normalized": k} for k in keys]}


def synthetic(per_class=40):
    rng = random.Random(7)
    recipes = []
    for label, signature in SIGNATURES.items():
        for _ in range(per_class):
            recipes.append(recipe(label, rng.sample(signature, 3) + rng.sample(SHARED, 3)))
    return recipes


def test_labels_are_normalized_deduplicated_and_filtered():
    rows = [recipe("India", ["a", "b"]), recipe("Hausa", ["c"]), recipe("African", ["d"]), recipe(None, ["e"]),
            recipe("Indian", ["x"]), recipe("Italian", ["x"]), recipe("Indian", ["a", "b"])]
    x, y, report = cuisine.examples(rows)
    assert cuisine.canonical_label("France") == "French" and cuisine.canonical_label("Asian") is None
    assert report["dropped_conflicting_duplicates"] == 1          # ("x",) labelled both Indian and Italian
    assert report["labelled_unique_ingredient_lists"] == 2        # ("a","b") Indian once, ("c",) Nigerian
    assert report["dropped_small_classes"] == {"Indian": 1, "Nigerian": 1}
    assert x == [] and y == []


def test_training_is_deterministic_and_split_has_no_shared_recipes():
    recipes = synthetic()
    model_a, report_a = cuisine.train(recipes)
    model_b, report_b = cuisine.train(recipes)
    assert report_a == report_b
    assert report_a["split"]["train"] + report_a["split"]["validation"] + report_a["split"]["test"] \
        == sum(report_a["data"]["class_counts"].values())
    assert report_a["test"]["macro_f1"] > report_a["baseline_majority_class"]["test_macro_f1"]


@pytest.fixture(scope="module")
def trained(tmp_path_factory):
    directory = tmp_path_factory.mktemp("models")
    model, report = cuisine.train(synthetic())
    card = cuisine.save(model, report, directory, {"source": "synthetic test fixture"})
    return directory, card


def test_classify_returns_ranked_labels_without_scores_and_abstains_on_unknown_input(trained):
    model, card = cuisine.load(trained[0])
    result = cuisine.classify(model, card, ["Garam masala", "2 tbsp ghee", "turmeric", "onion"])
    assert result["cuisine"] == "Indian" and result["status"] == "predicted"
    assert len(result["alternatives"]) == 2 and "Indian" not in result["alternatives"]
    assert not any(isinstance(v, float) for v in result.values())
    unknown = cuisine.classify(model, card, ["dragonfruit", "unobtainium"])
    assert unknown == {"modelVersion": card["version"], "recognizedIngredients": 0, "cuisine": None,
                       "status": "unknown", "alternatives": []}


def test_model_is_refused_when_missing_tampered_or_from_another_sklearn(trained, tmp_path):
    with pytest.raises(cuisine.ModelUnavailable):
        cuisine.load(tmp_path)
    directory, card = trained
    (tmp_path / "cuisine.json").write_text(json.dumps({**card, "model_sha256": "0" * 64}), encoding="utf-8")
    (tmp_path / card["model_file"]).write_bytes((directory / card["model_file"]).read_bytes())
    with pytest.raises(cuisine.ModelUnavailable, match="does not match"):
        cuisine.load(tmp_path)
    (tmp_path / "cuisine.json").write_text(json.dumps({**card, "scikit_learn": "0.0.1"}), encoding="utf-8")
    with pytest.raises(cuisine.ModelUnavailable, match="scikit-learn"):
        cuisine.load(tmp_path)


@pytest.fixture
def client(monkeypatch):
    token = "t" * 40
    monkeypatch.setenv("SERVICE_TO_SERVICE_SECRET", token)
    return TestClient(app), {"X-Service-Token": token}


def test_classify_endpoint(client, trained, monkeypatch, tmp_path):
    http, headers = client
    monkeypatch.setenv("MODEL_DIR", str(tmp_path))
    missing = http.post("/internal/v1/cuisine/classify", headers=headers, json={"ingredients": ["basil"]})
    assert missing.status_code == 503 and missing.json()["error"]["code"] == "MODEL_UNAVAILABLE"
    monkeypatch.setenv("MODEL_DIR", str(trained[0]))
    ok = http.post("/internal/v1/cuisine/classify", headers=headers,
                   json={"ingredients": ["basil", "parmesan", "pasta"]})
    assert ok.status_code == 200 and ok.json()["cuisine"] == "Italian"
    for body in ({"ingredients": []}, {"ingredients": ["  "]}, {"ingredients": ["x" * 101]},
                 {"ingredients": ["a"] * 101}, {"ingredients": ["a"], "userId": "x"}):
        assert http.post("/internal/v1/cuisine/classify", headers=headers, json=body).status_code == 400
    assert http.post("/internal/v1/cuisine/classify", json={"ingredients": ["basil"]}).status_code == 401


def test_waste_prediction_is_explicitly_unavailable_without_trained_model(client):
    http, headers = client
    body = {"daysToExpiry": 2, "quantityRemaining": 1.5, "unit": "kg", "category": "vegetables", "usedFraction": 0.25}
    response = http.post("/internal/v1/waste/predict", headers=headers, json=body)
    assert response.status_code == 200
    assert response.json()["status"] == "insufficient_data"
    assert response.json()["riskLabel"] is None and response.json()["riskScore"] is None
    assert http.post("/internal/v1/waste/predict", headers=headers,
                     json={**body, "usedFraction": 1.5}).status_code == 400
    assert http.post("/internal/v1/waste/predict", headers=headers, json={**body, "unit": "cup"}).status_code == 400
    assert http.post("/internal/v1/waste/predict", json=body).status_code == 401
