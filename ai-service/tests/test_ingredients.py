import re
from pathlib import Path

import pytest

from app.ingredients import allergens_for, contains_meat, dictionary, matches, normalize, parse_quantity

MIGRATION = next((Path(__file__).resolve().parents[2] / "database" / "migrations").glob("V1__*.sql")).read_text(
    encoding="utf-8")


@pytest.mark.parametrize(
    ("text", "key"),
    [
        ("2 large Tomatoes, chopped", "tomato"),
        ("1 medium onion, chopped thin", "onion"),
        ("½ tsp ground cumin", "cumin"),
        ("5 curry leaves", "curry leaf"),
        ("4 cloves garlic", "garlic"),
        ("Basmati Rice (washed)", "basmati rice"),
        ("3 green chillies", "green chilli"),
        ("salt to taste", "salt"),
        ("200g paneer", "paneer"),
        ("", ""),
    ],
)
def test_normalize(text, key):
    assert normalize(text) == key


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("2 eggs", (2.0, "piece", "eggs")),
        ("500 g rice", (500.0, "g", "rice")),
        ("1 1/2 kg potatoes", (1.5, "kg", "potatoes")),
        ("½ litre milk", (0.5, "l", "milk")),
        ("4-5 green chillies", (4.0, "piece", "green chillies")),
        ("2 cups flour", (473.176, "ml", "flour")),
        ("3 tbsp oil", (44.36, "ml", "oil")),
        ("1/2 tsp salt", (2.464, "ml", "salt")),
        ("12 ounces (340g) brown lentils", (340.194, "g", "(340g) brown lentils")),
        ("1 lb chicken", (453.592, "g", "chicken")),
        ("2 fl oz cream", (None, None, "fl oz cream")),
        ("a pinch of salt", (None, None, "a pinch of salt")),
        ("1 bunch spinach", (1.0, "bunch", "spinach")),
        ("6 medium cloves garlic", (None, None, "medium cloves garlic")),
        ("2 small stalks celery", (None, None, "small stalks celery")),
        ("1 container sour cream", (None, None, "container sour cream")),
        ("1⁄2 kg sugar", (0.5, "kg", "sugar")),
        ("2 large eggs", (2.0, "piece", "large eggs")),
        ("250 g chopped onions", (250.0, "g", "chopped onions")),
    ],
)
def test_parse_quantity_never_guesses_unknown_units(text, expected):
    assert parse_quantity(text) == expected


@pytest.mark.parametrize(
    ("key", "flags", "known"),
    [
        ("rice", set(), True),
        ("paneer", {"dairy"}, True),
        ("butter", {"dairy"}, True),
        ("coconut milk", {"tree_nuts"}, True),
        ("peanut butter", {"peanuts"}, False),
        ("eggplant", set(), True),
        ("rice noodle", set(), False),
        ("wheat flour", {"gluten"}, True),
        ("chickpea flour", set(), True),
        ("soy sauce", {"soy", "gluten"}, True),
        ("chicken stock", set(), False),
        ("vegetable oil", {"peanuts", "soy"}, True),
        ("oil", {"peanuts", "sesame", "soy"}, True),
        ("vinegar", {"gluten"}, True),
        ("asafoetida", {"gluten"}, True),
        ("oat", {"gluten"}, False),
        ("oat milk", {"gluten"}, False),
        ("almond milk", {"tree_nuts"}, False),
        ("garam masala", set(), False),
        ("mayonnaise", {"eggs"}, False),
        ("fish sauce", {"fish"}, False),
        ("dashi", {"fish"}, False),
        ("seafood", {"fish", "shellfish"}, False),
        ("chocolate", set(), False),
        ("nutmeg", set(), True),
        ("pesto", set(), False),
        ("cream tartar", set(), False),
        ("prawn", {"shellfish"}, True),
    ],
)
def test_allergen_lookup_is_conservative(key, flags, known):
    assert allergens_for(key) == (flags, known)


@pytest.mark.parametrize(
    ("text", "key", "flags"),
    [
        ("2 tbsp gram flour", "chickpea flour", set()),       # 'gram' is also a unit word
        ("400 g garbanzo beans", "chickpea", set()),
        ("1 cup Yoghurt", "yogurt", {"dairy"}),
        ("pinch of hing", "asafoetida", {"gluten"}),
        ("2 hard-boiled eggs", "egg", {"eggs"}),
        ("1 Jalapeño, sliced", "jalapeno", set()),       # accents are folded, not deleted
        ("Crème fraîche", "creme fraiche", {"dairy"}),
        ("groundnut oil", "peanut oil", {"peanuts"}),
        ("  SALT  ", "salt", set()),
    ],
)
def test_synonyms_and_spelling_variants_reach_the_known_entry(text, key, flags):
    assert normalize(text) == key
    assert allergens_for(key) == (flags, True)


@pytest.mark.parametrize(
    ("key", "flags"),
    [
        ("nutmeg", set()), ("butternut squash", set()), ("water chestnut", set()), ("eggplant", set()),
        ("cream tartar", set()), ("coconut milk", {"tree_nuts"}), ("buckwheat", set()), ("goat", set()),
        ("doughnut", set()), ("codswallop", set()), ("rice noodle", set()), ("cocoa butter", set()),
        ("butter bean", set()), ("peanut butter", {"peanuts"}),
    ],
)
def test_whole_word_matching_and_exceptions_avoid_false_flags(key, flags):
    assert allergens_for(key)[0] == flags


def test_dictionary_invariants():
    d = dictionary()
    approved = set(re.search(r"allergen_code AS text CHECK \(VALUE IN \(([^)]*)\)", MIGRATION).group(1)
                   .replace("'", "").replace(" ", "").replace("\n", "").split(","))
    used = set(d["keywords"]) | set(d["keyword_exceptions"]) | {c for v in d["known"].values() for c in v}
    assert used <= approved and set(d["keywords"]) == approved
    assert [k for k in d["known"] if normalize(k) != k] == []                       # no unreachable entries
    assert [p for ps in d["keyword_exceptions"].values() for p in ps if normalize(p) != p] == []
    assert [s for s, target in d["synonyms"].items() if target not in d["known"] or normalize(s) != target] == []
    assert [(a, w) for a, ws in d["keywords"].items() for w in ws if a not in allergens_for(normalize(w))[0]] == []
    assert all(len(set(ws)) == len(ws) for ws in d["keywords"].values())


def test_unknown_and_empty_ingredients_are_never_known_safe():
    assert allergens_for("") == (set(), False)
    assert allergens_for("dragon fruit chutney") == (set(), False)
    assert allergens_for(normalize("4 whole cloves")) == (set(), False)   # 'clove' is a unit word: key is empty


def test_meat_detection():
    assert contains_meat("chicken breast")
    assert contains_meat("beef stock cube")
    assert not contains_meat("chickpea")
    assert not contains_meat("mushroom")


@pytest.mark.parametrize(
    ("pantry", "recipe", "expected"),
    [
        ("rice", "rice", True),
        ("basmati rice", "rice", True),
        ("rice", "brown rice", True),
        ("onion", "red onion", True),
        ("chicken", "chicken stock", False),
        ("chickpea", "chicken", False),
        ("", "rice", False),
    ],
)
def test_matching(pantry, recipe, expected):
    assert matches(pantry, recipe) is expected
