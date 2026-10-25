"""Parser tests on small synthetic records written in each source's format (not real recipes)."""

from app.sources import parse_mealdb, parse_wikibooks

CUISINES = {"Category:Indian recipes": "Indian"}

WIKITEXT = """{{recipesummary|category=Side dish recipes|servings=4|time=20 minutes}}
{{recipe}}

'''Test Potato Dish''' is a synthetic fixture.

==Ingredients==
* 1 [[Cookbook:Tablespoon|tablespoon]] [[Cookbook:Olive Oil|olive oil]]
* ½ teaspoon [[Cookbook:Mustard Seed|mustard seed]]
* 4 large [[Cookbook:Potato|potatoes]], cubed
* 200 g [[Cookbook:Paneer|paneer]]
* [[Cookbook:Salt|Salt]] to taste

==Procedure==
# Heat the '''oil'''. <ref>note</ref>
# Add [[Cookbook:Potato|potatoes]] and cook.
"""


def page(**overrides):
    base = {"pageid": 123, "revid": 456, "title": "Cookbook:Test Potato Dish", "wikitext": WIKITEXT,
            "categories": ["Category:Indian recipes", "Category:Vegetarian recipes", "Category:Recipes"]}
    return {**base, **overrides}


def test_wikibooks_recipe_keeps_source_license_and_parsed_ingredients():
    recipe, skipped = parse_wikibooks(page(), CUISINES)
    assert skipped is None
    assert recipe["title"] == "Test Potato Dish"
    assert recipe["source_key"] == "123"
    assert recipe["source_url"].endswith("oldid=456")
    assert recipe["source_license"] == "CC BY-SA 4.0"
    assert "Wikibooks contributors" in recipe["attribution"] and "Modified" in recipe["attribution"]
    assert recipe["cuisine"] == "Indian"
    assert recipe["servings"] == 4.0
    assert recipe["diet_labels"] == ["vegetarian"]
    keys = [i["normalized"] for i in recipe["ingredients"]]
    assert keys == ["olive oil", "mustard seed", "potato", "paneer", "salt"]
    paneer = recipe["ingredients"][3]
    assert (paneer["quantity"], paneer["unit"], paneer["allergens"]) == (200.0, "g", ["dairy"])
    oil = recipe["ingredients"][0]
    assert (oil["quantity"], oil["unit"]) == (None, None)
    assert recipe["allergen_metadata_complete"] is True
    assert recipe["instructions"] == "Heat the oil.\nAdd potatoes and cook."


def test_technique_links_are_not_taken_as_ingredients():
    text = WIKITEXT.replace("* 4 large [[Cookbook:Potato|potatoes]], cubed",
                            "* 4 large [[Cookbook:Potato|potatoes]], [[Cookbook:Dicing|diced]]")
    text = text.replace("* 200 g [[Cookbook:Paneer|paneer]]", "* 200 g [[Cookbook:Chopping|chopped]] [[Cookbook:Paneer|paneer]]")
    recipe, _ = parse_wikibooks(page(wikitext=text), CUISINES)
    assert [i["normalized"] for i in recipe["ingredients"]][2:4] == ["potato", "paneer"]


def test_unknown_ingredient_makes_allergen_data_incomplete():
    text = WIKITEXT.replace("* [[Cookbook:Salt|Salt]] to taste", "* 2 tbsp [[Cookbook:Pesto|pesto]]")
    recipe, _ = parse_wikibooks(page(wikitext=text), CUISINES)
    assert recipe["allergen_metadata_complete"] is False


def test_meat_ingredient_removes_vegetarian_label():
    text = WIKITEXT.replace("* [[Cookbook:Salt|Salt]] to taste", "* 100 g [[Cookbook:Bacon|bacon]]")
    recipe, _ = parse_wikibooks(page(wikitext=text), CUISINES)
    assert recipe["diet_labels"] == []


def test_wikibooks_pages_without_ingredients_are_skipped_with_reason():
    assert parse_wikibooks(page(wikitext="Just prose."), CUISINES) == (None, "no ingredients section")
    one = "==Ingredients==\n* 1 [[Cookbook:Egg|egg]]\n"
    assert parse_wikibooks(page(wikitext=one), CUISINES) == (None, "fewer than 2 ingredients")


def meal(**overrides):
    base = {"idMeal": "99999", "strMeal": "Synthetic Dal", "strCategory": "Vegetarian", "strArea": "Indian",
            "strInstructions": "Cook the lentils.", "strSource": "https://example.org/dal",
            "strIngredient1": "Red Lentils", "strMeasure1": "250g", "strIngredient2": "Turmeric",
            "strMeasure2": "1 tsp", "strIngredient3": "Ghee", "strMeasure3": "2 tbs", "strIngredient4": "",
            "strMeasure4": " "}
    return {**base, **overrides}


def test_mealdb_recipe_credits_source_and_keeps_measures_as_text():
    recipe, skipped = parse_mealdb(meal())
    assert skipped is None
    assert recipe["source_name"] == "TheMealDB"
    assert recipe["source_url"] == "https://www.themealdb.com/meal/99999"
    assert "TheMealDB" in recipe["attribution"] and "https://example.org/dal" in recipe["attribution"]
    assert recipe["cuisine"] == "Indian"
    assert recipe["diet_labels"] == ["vegetarian"]
    lentils, turmeric, ghee = recipe["ingredients"]
    assert (lentils["normalized"], lentils["quantity"], lentils["unit"]) == ("red lentil", 250.0, "g")
    assert (turmeric["quantity"], turmeric["unit"]) == (None, None)
    assert ghee["allergens"] == ["dairy"]
    assert recipe["allergen_metadata_complete"] is True


def test_mealdb_category_is_not_trusted_against_meat():
    recipe, _ = parse_mealdb(meal(strIngredient4="Chicken", strMeasure4="200g"))
    assert recipe["diet_labels"] == []
