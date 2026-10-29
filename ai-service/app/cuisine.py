"""Cuisine classifier (FR-013): TF-IDF over normalized ingredient names + logistic regression.

Training data is real source labels only (Wikibooks cuisine categories, TheMealDB area), normalized by LABEL_ALIASES.
Continent-level groups ("African", "Asian") are not cuisines and are dropped, as are classes with fewer than
MIN_CLASS_EXAMPLES recipes. Duplicate ingredient lists are kept once, and dropped entirely when their labels
disagree, so the same recipe cannot sit in both training and test data. The split is stratified with a fixed seed.

Predictions carry no probability: the scores are not calibrated, so only a ranked list is returned. Input with no
ingredient the model has seen gets "unknown" instead of a guess.

Acceptance (TBD-027, D-048): a model is served only if its macro-F1, averaged over 5x5 repeated stratified
cross-validation, reaches ACCEPTANCE["threshold"]. The card records that check; load() repeats it.
"""

import hashlib
import json
from collections import Counter
from datetime import UTC, datetime
from pathlib import Path

import joblib
import sklearn
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import classification_report, confusion_matrix, f1_score
from sklearn.model_selection import RepeatedStratifiedKFold, cross_val_score, train_test_split
from sklearn.naive_bayes import ComplementNB
from sklearn.pipeline import Pipeline

from app.ingredients import normalize

MODEL_NAME = "cuisine"
MIN_CLASS_EXAMPLES = 30
SEED = 20261029
C_GRID = (0.3, 1.0, 3.0, 10.0)
CV_SPLITS, CV_REPEATS = 5, 5

# Provisional pass mark (TBD-027, D-048), fixed on 2026-10-29 before the shipped model was scored: the untuned
# ComplementNB reference baseline's cross-validated macro-F1 mean + 2 sd (0.4981 + 2 x 0.0292 = 0.5564), rounded up.
# There is no product requirement yet; raise it, never lower it, to make a model pass.
ACCEPTANCE = {"metric": "macro_f1", "estimate": f"mean over {CV_REPEATS}x{CV_SPLITS} repeated stratified cross-validation",
              "threshold": 0.56, "status": "provisional", "decided": "2026-10-29", "decision": "D-048"}

LABEL_ALIASES = {
    "United States": "American", "Southwestern": "American", "Native American": "American", "Canadian": "Canadian",
    "England": "British", "English": "British", "British": "British", "Scottish": "British", "Welsh": "British",
    "France": "French", "India": "Indian", "Italy": "Italian", "Spain": "Spanish", "Mexico": "Mexican",
    "China": "Chinese", "Japan": "Japanese", "Thailand": "Thai", "Turkey": "Turkish", "Norway": "Norwegian",
    "Netherlands": "Dutch", "Argentina": "Argentine", "Venezuela": "Venezuelan", "Slovakia": "Slovak",
    "National Nigerian": "Nigerian", "Hausa": "Nigerian", "Yoruba": "Nigerian", "Igbo": "Nigerian",
}
NOT_A_CUISINE = {"African", "Asian", "European", "Middle Eastern", "South Asian", "West Asian", "Caribbean",
                 "Mediterranean", "Pacific", "Jewish", "Family heritage"}


def canonical_label(raw: str | None) -> str | None:
    if not raw:
        return None
    label = LABEL_ALIASES.get(raw.strip(), raw.strip())
    return None if label in NOT_A_CUISINE else label


def features(ingredient_keys) -> list[str]:
    """Each normalized ingredient as one token, plus its single words ("basmati_rice", "basmati", "rice")."""
    tokens = []
    for key in ingredient_keys:
        key = key.strip()
        if key:
            tokens.append(key.replace(" ", "_"))
            tokens.extend(key.split())
    return tokens


def _identity(tokens):
    return tokens


def examples(recipes: list[dict]) -> tuple[list[list[str]], list[str], dict]:
    """(token lists, labels, report) after label normalization, de-duplication and the minimum class size."""
    by_ingredients: dict[tuple, set] = {}
    for recipe in recipes:
        label = canonical_label(recipe.get("cuisine"))
        keys = tuple(sorted({i["normalized"] for i in recipe["ingredients"] if i["normalized"]}))
        if label and keys:
            by_ingredients.setdefault(keys, set()).add(label)
    conflicting = sum(1 for labels in by_ingredients.values() if len(labels) > 1)
    unique = [(keys, labels.pop()) for keys, labels in by_ingredients.items() if len(labels) == 1]
    counts = Counter(label for _, label in unique)
    kept = sorted((keys, label) for keys, label in unique if counts[label] >= MIN_CLASS_EXAMPLES)
    report = {"labelled_unique_ingredient_lists": len(unique), "dropped_conflicting_duplicates": conflicting,
              "dropped_small_classes": {k: v for k, v in sorted(counts.items()) if v < MIN_CLASS_EXAMPLES},
              "class_counts": dict(sorted(Counter(label for _, label in kept).items()))}
    return [features(keys) for keys, _ in kept], [label for _, label in kept], report


def _pipeline(c: float) -> Pipeline:
    return Pipeline([
        ("tfidf", TfidfVectorizer(analyzer=_identity, sublinear_tf=True, min_df=2)),
        ("model", LogisticRegression(C=c, class_weight="balanced", max_iter=2000)),
    ])


def _reference_baseline() -> Pipeline:
    return Pipeline([
        ("tfidf", TfidfVectorizer(analyzer=_identity, sublinear_tf=True, min_df=2)),
        ("model", ComplementNB()),
    ])


def _cross_validated(estimator, x, y) -> dict:
    folds = RepeatedStratifiedKFold(n_splits=CV_SPLITS, n_repeats=CV_REPEATS, random_state=SEED)
    scores = cross_val_score(estimator, x, y, cv=folds, scoring="f1_macro")
    return {"mean": round(float(scores.mean()), 4), "sd": round(float(scores.std(ddof=1)), 4),
            "min": round(float(scores.min()), 4), "max": round(float(scores.max()), 4), "folds": len(scores)}


def accepted(observed: float | None) -> bool:
    return observed is not None and observed >= ACCEPTANCE["threshold"]


def train(recipes: list[dict]) -> tuple[Pipeline, dict]:
    """Train on 70%, pick C on 15% validation, report on the untouched 15% test split. Deterministic."""
    x, y, data_report = examples(recipes)
    x_train, x_rest, y_train, y_rest = train_test_split(x, y, test_size=0.3, stratify=y, random_state=SEED)
    x_val, x_test, y_val, y_test = train_test_split(x_rest, y_rest, test_size=0.5, stratify=y_rest, random_state=SEED)
    validation = {}
    for c in C_GRID:
        validation[c] = f1_score(y_val, _pipeline(c).fit(x_train, y_train).predict(x_val), average="macro")
    best_c = max(C_GRID, key=lambda c: (validation[c], -c))
    model = _pipeline(best_c).fit(x_train, y_train)
    predicted = model.predict(x_test)
    labels = sorted(set(y))
    model_cv, baseline_cv = _cross_validated(_pipeline(best_c), x, y), _cross_validated(_reference_baseline(), x, y)
    majority = Counter(y_train).most_common(1)[0][0]
    report = {
        "data": data_report,
        "split": {"train": len(y_train), "validation": len(y_val), "test": len(y_test), "seed": SEED},
        "chosen_C": best_c,
        "validation_macro_f1_by_C": {str(c): round(v, 4) for c, v in validation.items()},
        "test": {
            "macro_f1": round(f1_score(y_test, predicted, average="macro"), 4),
            "accuracy": round(sum(p == t for p, t in zip(predicted, y_test, strict=True)) / len(y_test), 4),
            "per_class": {label: {k: round(v, 4) if isinstance(v, float) else v for k, v in m.items()}
                          for label, m in classification_report(y_test, predicted, output_dict=True,
                                                                zero_division=0).items()
                          if label in set(y)},
            "confusion_matrix": {"labels": labels, "rows_true_columns_predicted":
                                 confusion_matrix(y_test, predicted, labels=labels).tolist()},
        },
        "acceptance": {**ACCEPTANCE, "observed": model_cv["mean"], "passed": accepted(model_cv["mean"]),
                       "evaluated": datetime.now(UTC).date().isoformat(),
                       "cross_validation": {"model": model_cv, "reference_baseline_complement_nb": baseline_cv}},
        "baseline_majority_class": {
            "label": majority,
            "test_macro_f1": round(f1_score(y_test, [majority] * len(y_test), average="macro"), 4),
            "test_accuracy": round(sum(t == majority for t in y_test) / len(y_test), 4),
        },
    }
    return model, report


def save(model: Pipeline, report: dict, directory: Path, data_checksums: dict) -> dict:
    """Write the model and its card. The card records the model's SHA-256, which load() checks."""
    directory.mkdir(parents=True, exist_ok=True)
    version = datetime.now(UTC).strftime("%Y%m%d%H%M%S")
    model_path = directory / f"{MODEL_NAME}-{version}.joblib"
    joblib.dump(model, model_path)
    card = {"name": MODEL_NAME, "version": version, "algorithm": "TF-IDF + LogisticRegression",
            "scikit_learn": sklearn.__version__, "model_file": model_path.name,
            "model_sha256": hashlib.sha256(model_path.read_bytes()).hexdigest(), "training_data": data_checksums,
            "label_aliases": LABEL_ALIASES, "excluded_labels": sorted(NOT_A_CUISINE),
            "min_class_examples": MIN_CLASS_EXAMPLES, **report}
    (directory / f"{MODEL_NAME}.json").write_text(json.dumps(card, indent=1, ensure_ascii=False), encoding="utf-8")
    return card


class ModelUnavailable(RuntimeError):
    pass


def load(directory: Path) -> tuple[Pipeline, dict]:
    """Load the current model only if the card exists, the file hash matches and scikit-learn versions agree.
    joblib files can run code when loaded, so only files from the configured local directory are trusted."""
    card_path = directory / f"{MODEL_NAME}.json"
    if not card_path.is_file():
        raise ModelUnavailable("no trained model")
    card = json.loads(card_path.read_text(encoding="utf-8"))
    model_path = directory / Path(card["model_file"]).name
    if not model_path.is_file() or hashlib.sha256(model_path.read_bytes()).hexdigest() != card["model_sha256"]:
        raise ModelUnavailable("model file missing or does not match its card")
    if card["scikit_learn"] != sklearn.__version__:
        raise ModelUnavailable("model was trained with a different scikit-learn version")
    check = card.get("acceptance") or {}
    if check.get("metric") != ACCEPTANCE["metric"] or not accepted(check.get("observed")):
        raise ModelUnavailable("model has not passed the acceptance check (D-048)")
    return joblib.load(model_path), card


def classify(model: Pipeline, card: dict, ingredient_names: list[str], top: int = 3) -> dict:
    tokens = features(normalize(name) for name in ingredient_names)
    vocabulary = model.named_steps["tfidf"].vocabulary_
    known = sorted({t for t in tokens if t in vocabulary})
    base = {"modelVersion": card["version"], "recognizedIngredients": len(known)}
    if not known:
        return {**base, "cuisine": None, "status": "unknown", "alternatives": []}
    probabilities = model.predict_proba([tokens])[0]
    ranked = [str(label) for _, label in sorted(zip(probabilities, model.classes_, strict=True), key=lambda p: (-p[0], p[1]))]
    return {**base, "cuisine": ranked[0], "status": "predicted", "alternatives": ranked[1:top]}
