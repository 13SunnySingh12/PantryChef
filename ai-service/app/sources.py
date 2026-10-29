"""Turn raw source records into PantryChef recipe rows (schema V1/V2). Pure functions: no network, no database.

Every recipe keeps its source identity, URL, license and attribution (FR-010). Nothing is invented: a field the
source does not provide stays None/empty, and allergen status is derived only via the versioned dictionary.
"""

import re
import urllib.parse

from app.ingredients import allergen_source, allergens_for, contains_meat, normalize, parse_quantity

WIKIBOOKS_LICENSE = "CC BY-SA 4.0"
MEALDB_LICENSE = "TheMealDB API terms (development/educational use)"

UNIT_PAGES = {
    "cup", "tablespoon", "teaspoon", "gram", "kilogram", "litre", "liter", "millilitre", "milliliter", "ounce",
    "pound", "pinch", "dash", "clove", "can", "slice", "sprig", "bunch", "dozen", "inch", "centimetre", "pint",
    "quart", "fluid ounce", "stick", "head",
}
# Cookbook technique/glossary pages that are linked inside ingredient lines but are not foods.
NOT_FOOD_PAGES = {
    "chopping", "chop", "dicing", "dice", "slicing", "slice", "grating", "grate", "mincing", "mince (cutting)",
    "peeling", "crushing", "each", "boiling", "frying", "baking", "roasting", "julienne", "whisk", "whisking",
    "sifting", "blanching", "mashing", "zest", "zesting", "grinding", "melting", "beating", "cubing", "cube",
    "shredding", "soaking", "simmering", "sauteing", "sautéing", "marinating", "kneading", "brunoise", "chiffonade",
    "measurement", "units of measurement", "optional", "to taste",
}
# Source course labels. Order matters: a page in both "Dessert" and "Main course" is not planned as a main.
WIKIBOOKS_COURSES = [
    ("dessert", {"Category:Recipes for dessert", "Category:Dessert recipes", "Category:Recipes for frozen desserts"}),
    ("drink", {"Category:Recipes for beverages", "Category:Recipes for alcoholic beverages"}),
    ("condiment", {"Category:Recipes for sauces and condiments", "Category:Recipes for salad dressing",
                   "Category:Recipes for pasta sauce", "Category:Recipes for barbecue sauce"}),
    ("breakfast", {"Category:Breakfast recipes"}),
    ("main", {"Category:Main course recipes", "Category:Dinner recipes", "Category:Recipes for soup",
              "Category:Soup recipes", "Category:Recipes for salad"}),
    ("side", {"Category:Side dish recipes", "Category:Appetizer recipes", "Category:Snack recipes"}),
]
MEALDB_COURSES = {
    "breakfast": "breakfast", "dessert": "dessert", "side": "side", "starter": "side",
    **dict.fromkeys(["beef", "chicken", "seafood", "pork", "lamb", "goat", "pasta", "vegetarian", "vegan"], "main"),
}
DIET_CATEGORIES = {
    "Category:Vegan recipes": "vegan",
    "Category:Vegetarian recipes": "vegetarian",
    "Category:Gluten-free recipes": "gluten_free",
    "Category:Naturally gluten-free recipes": "gluten_free",
}
INGREDIENT_HEADINGS = re.compile(r"^==+\s*ingredients?\s*==+\s*$", re.I | re.M)
METHOD_HEADINGS = re.compile(r"^==+\s*(procedure|directions|method|preparation|instructions|steps)\s*==+\s*$",
                             re.I | re.M)
ANY_HEADING = re.compile(r"^==+[^=].*?==+\s*$", re.M)
LINK = re.compile(r"\[\[([^\[\]|]+)(?:\|([^\[\]]*))?\]\]")


def _section(wikitext: str, heading: re.Pattern) -> str | None:
    start = heading.search(wikitext)
    if not start:
        return None
    following = ANY_HEADING.search(wikitext, start.end())
    return wikitext[start.end(): following.start() if following else len(wikitext)]


def plain_text(wikitext: str) -> str:
    """Wiki markup to plain text (rendered as text by the UI, never as HTML)."""
    text = re.sub(r"<ref[^>]*?/>|<ref.*?</ref>", "", wikitext, flags=re.S)
    text = re.sub(r"\{\{[^{}]*\}\}", "", text)
    text = re.sub(r"\[\[(?:File|Image):[^\]]*\]\]", "", text, flags=re.I)
    text = LINK.sub(lambda m: m.group(2) if m.group(2) is not None else m.group(1).split(":")[-1], text)
    text = re.sub(r"\[https?://\S+ ([^\]]+)\]", r"\1", text)
    text = re.sub(r"'{2,}", "", text)
    text = re.sub(r"<[^>]+>", "", text)
    lines = [re.sub(r"^[*#:;]+\s*", "", line).strip() for line in text.splitlines()]
    return "\n".join(line for line in lines if line)


def _ingredient(display: str, name_hint: str | None) -> dict:
    quantity, unit, rest = parse_quantity(display)
    key = normalize(name_hint) if name_hint else normalize(rest)
    flags, known = allergens_for(key)
    return {"name": display, "normalized": key, "quantity": quantity, "unit": unit, "allergens": sorted(flags),
            "known": known}


def _wikibooks_ingredient(line: str) -> dict | None:
    targets = [m.group(1).split(":")[-1].strip() for m in LINK.finditer(line)]
    foods = [t for t in targets if t.lower() not in UNIT_PAGES and t.lower() not in NOT_FOOD_PAGES]
    display = plain_text(line)
    if not display:
        return None
    return _ingredient(display, foods[0] if foods else None)


def _finish(recipe: dict, ingredients: list[dict], labels: set[str]) -> dict:
    """Allergen completeness and label sanity shared by both sources."""
    ingredients = [i for i in ingredients if i["normalized"]]
    complete = bool(ingredients) and all(i["known"] for i in ingredients)
    if any(contains_meat(i["normalized"]) for i in ingredients):
        labels -= {"vegan", "vegetarian"}
    recipe.update(
        ingredients=ingredients,
        diet_labels=sorted(labels),
        allergen_metadata_complete=complete,
        allergen_source=allergen_source(),
    )
    return recipe


def parse_wikibooks(page: dict, cuisines: dict[str, str]) -> tuple[dict | None, str | None]:
    """(recipe, None) or (None, reason it was skipped)."""
    wikitext = page.get("wikitext") or ""
    block = _section(wikitext, INGREDIENT_HEADINGS)
    if block is None:
        return None, "no ingredients section"
    lines = [line for line in block.splitlines() if line.lstrip().startswith("*")]
    ingredients = [i for i in (_wikibooks_ingredient(line) for line in lines) if i]
    if len(ingredients) < 2:
        return None, "fewer than 2 ingredients"
    method = _section(wikitext, METHOD_HEADINGS)
    title = page["title"].removeprefix("Cookbook:")
    url = f"https://en.wikibooks.org/w/index.php?title={urllib.parse.quote(page['title'].replace(' ', '_'))}" \
          f"&oldid={page['revid']}"
    servings = re.search(r"\|\s*servings\s*=\s*(\d+)", wikitext, re.I)
    categories = page.get("categories", [])
    cuisine = next((cuisines[c] for c in categories if c in cuisines), None)
    recipe = {
        "source_name": "Wikibooks Cookbook",
        "source_key": str(page["pageid"]),
        "source_url": url,
        "source_license": WIKIBOOKS_LICENSE,
        "attribution": f'"{title}" from the Wikibooks Cookbook by Wikibooks contributors, licensed under CC BY-SA 4.0 '
                       f"(https://creativecommons.org/licenses/by-sa/4.0/). Modified: ingredients parsed and text "
                       f"reformatted by PantryChef.",
        "dataset_version": f"wikibooks:rev{page['revid']}",
        "title": title,
        "instructions": plain_text(method) if method else None,
        "cuisine": cuisine,
        "servings": float(servings.group(1)) if servings and int(servings.group(1)) > 0 else None,
        "course": next((course for course, names in WIKIBOOKS_COURSES if names & set(categories)), None),
    }
    labels = {DIET_CATEGORIES[c] for c in categories if c in DIET_CATEGORIES}
    return _finish(recipe, ingredients, labels), None


def parse_mealdb(meal: dict) -> tuple[dict | None, str | None]:
    ingredients = []
    for n in range(1, 21):
        name = (meal.get(f"strIngredient{n}") or "").strip()
        if not name:
            continue
        measure = (meal.get(f"strMeasure{n}") or "").strip()
        ingredients.append(_ingredient(f"{measure} {name}".strip(), name))
    if len(ingredients) < 2:
        return None, "fewer than 2 ingredients"
    category = (meal.get("strCategory") or "").strip()
    source = (meal.get("strSource") or "").strip()
    recipe = {
        "source_name": "TheMealDB",
        "source_key": meal["idMeal"],
        "source_url": f"https://www.themealdb.com/meal/{meal['idMeal']}",
        "source_license": MEALDB_LICENSE,
        "attribution": "Recipe data from TheMealDB (https://www.themealdb.com)"
                       + (f"; original source: {source}" if source.startswith("http") else ""),
        "dataset_version": f"themealdb:{meal.get('dateModified') or 'unversioned'}",
        "title": meal["strMeal"].strip(),
        "instructions": (meal.get("strInstructions") or "").strip() or None,
        "cuisine": (meal.get("strArea") or "").strip() or None,
        "servings": None,
        "course": MEALDB_COURSES.get(category.lower()),
    }
    labels = {"vegan": {"vegan"}, "vegetarian": {"vegetarian"}}.get(category.lower(), set())
    return _finish(recipe, ingredients, labels), None
