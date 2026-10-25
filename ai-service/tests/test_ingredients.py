import pytest

from app.ingredients import allergens_for, contains_meat, matches, normalize, parse_quantity


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
        ("2 cups flour", (None, None, "cups flour")),
        ("3 tbsp oil", (None, None, "tbsp oil")),
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
        ("vegetable oil", set(), True),
        ("chocolate", set(), False),
        ("nutmeg", set(), True),
        ("pesto", set(), False),
        ("cream tartar", set(), False),
        ("prawn", {"shellfish"}, True),
    ],
)
def test_allergen_lookup_is_conservative(key, flags, known):
    assert allergens_for(key) == (flags, known)


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
