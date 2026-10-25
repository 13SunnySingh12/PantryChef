"""Ingredient names, quantities and allergen flags.

normalize() turns free text ("2 large Tomatoes, chopped") into a matching key ("tomato"). The display text is
always kept separately; the key is only for matching and dictionary lookups.
"""

import json
import re
from fractions import Fraction
from functools import lru_cache
from pathlib import Path

DICTIONARY_PATH = Path(__file__).resolve().parents[2] / "database" / "reference" / "ingredient_allergens.json"

# Units that map onto the approved vocabulary (A-04). Anything else (cup, tbsp, oz...) stays unresolved.
UNIT_WORDS = {
    "g": "g", "gram": "g", "grams": "g", "gm": "g", "gms": "g",
    "kg": "kg", "kilogram": "kg", "kilograms": "kg", "kilo": "kg", "kilos": "kg",
    "ml": "ml", "millilitre": "ml", "milliliter": "ml", "millilitres": "ml", "milliliters": "ml",
    "l": "l", "litre": "l", "liter": "l", "litres": "l", "liters": "l",
    "dozen": "dozen", "bunch": "bunch", "bunches": "bunch",
    "pack": "pack", "packs": "pack", "packet": "pack", "packets": "pack", "package": "pack", "packages": "pack",
}
OTHER_UNITS = {
    "cup", "cups", "c", "tbsp", "tbs", "tbsps", "tablespoon", "tablespoons", "tsp", "tsps", "teaspoon", "teaspoons",
    "oz", "ounce", "ounces", "lb", "lbs", "pound", "pounds", "pinch", "pinches", "dash", "dashes", "clove", "cloves",
    "can", "cans", "tin", "tins", "slice", "slices", "stick", "sticks", "sprig", "sprigs", "handful", "handfuls",
    "piece", "pieces", "head", "heads", "jar", "jars", "bottle", "bottles", "drop", "drops", "pint", "pints",
    "quart", "quarts", "fl", "dl", "cl", "inch", "inches", "cm", "knob", "stalk", "stalks", "container",
    "containers", "carton", "cartons", "tub", "tubs", "bag", "bags", "box", "boxes", "strip", "strips", "fillet",
    "fillets", "rasher", "rashers", "scoop", "scoops", "envelope", "envelopes", "sachet", "sachets",
}
DESCRIPTORS = {
    "fresh", "freshly", "large", "medium", "small", "big", "chopped", "finely", "roughly", "coarsely", "minced",
    "diced", "sliced", "thinly", "grated", "crushed", "peeled", "deseeded", "seeded", "boiled", "cooked", "raw",
    "dried", "dry", "frozen", "ripe", "optional", "about", "approx", "approximately", "whole", "halved", "quartered",
    "cubed", "shredded", "softened", "melted", "beaten", "lightly", "warm", "hot", "cold", "room", "temperature",
    "to", "taste", "for", "garnish", "of", "a", "an", "some", "few", "extra", "plus", "more", "or", "and", "the",
    "good", "quality", "organic", "level", "heaped", "rounded", "packed", "firmly", "ground", "split",
}
UNICODE_FRACTIONS = {"½": "1/2", "¼": "1/4", "¾": "3/4", "⅓": "1/3", "⅔": "2/3", "⅛": "1/8", "⅜": "3/8", "⅝": "5/8",
                     "⅞": "7/8"}
_QUANTITY = re.compile(r"^\s*(\d+\s+\d+/\d+|\d+/\d+|\d+(?:\.\d+)?)(?:\s*[-–]\s*\d+(?:[./]\d+)?)?\s*")


IRREGULAR = {"leaves": "leaf", "halves": "half", "loaves": "loaf", "chillies": "chilli", "chilies": "chili",
             "molasses": "molasses", "asparagus": "asparagus", "hummus": "hummus", "couscous": "couscous"}


def singular(word: str) -> str:
    if word in IRREGULAR:
        return IRREGULAR[word]
    if len(word) <= 3 or word.endswith(("ss", "us", "is")):
        return word
    if word.endswith("ies"):
        return word[:-3] + "y"
    if word.endswith("oes"):
        return word[:-2]
    if word.endswith(("ches", "shes", "xes")):
        return word[:-2]
    return word[:-1] if word.endswith("s") else word


def normalize(text: str) -> str:
    """Matching key: lowercase, no quantities/units/prep words/brackets/punctuation, last word singular."""
    text = text.lower().replace("⁄", "/")
    for symbol, ascii_fraction in UNICODE_FRACTIONS.items():
        text = text.replace(symbol, " " + ascii_fraction + " ")
    text = re.sub(r"\(.*?\)", " ", text).split(",")[0]
    words = [w for w in re.sub(r"[^a-z0-9 /'-]", " ", text).replace("-", " ").split()
             if not w[0].isdigit() and w not in DESCRIPTORS and w not in UNIT_WORDS and w not in OTHER_UNITS]
    if not words:
        return ""
    words[-1] = singular(words[-1])
    return " ".join(words).strip(" '")


def parse_quantity(text: str) -> tuple[float | None, str | None, str]:
    """Leading amount and approved unit, plus the rest of the text. Unknown units give (None, None, text):
    no quantity is ever guessed. A bare count ("2 eggs") is a piece count."""
    text = text.replace("⁄", "/")
    for symbol, ascii_fraction in UNICODE_FRACTIONS.items():
        text = text.replace(symbol, " " + ascii_fraction)
    match = _QUANTITY.match(text)
    if not match:
        return None, None, text.strip()
    amount = float(sum(Fraction(part) for part in match.group(1).split()))
    rest = text[match.end():].strip()
    words = rest.split()
    # the unit is the first word after the amount that is not a descriptor ("6 medium cloves garlic")
    position = next((n for n, w in enumerate(words) if w.lower().strip(".,") not in DESCRIPTORS), len(words))
    first = words[position].lower().strip(".,") if position < len(words) else ""
    if first in UNIT_WORDS:
        return amount, UNIT_WORDS[first], " ".join(words[:position] + words[position + 1:])
    if first in OTHER_UNITS or not rest:
        return None, None, rest
    return amount, "piece", rest


@lru_cache(maxsize=1)
def dictionary() -> dict:
    return json.loads(DICTIONARY_PATH.read_text(encoding="utf-8"))


def allergens_for(key: str) -> tuple[set[str], bool]:
    """(allergen flags, known) for one normalized ingredient. Keywords add flags; only an exact 'known' entry makes
    the ingredient's allergen status known."""
    data = dictionary()
    flags = set(data["known"].get(key, []))
    for allergen, words in data["keywords"].items():
        masked = key
        for phrase in data["keyword_exceptions"].get(allergen, []):
            masked = re.sub(rf"\b{re.escape(phrase)}\b", " ", masked)
        if any(re.search(rf"\b{re.escape(word)}\b", masked) for word in words):
            flags.add(allergen)
    return flags, key in data["known"]


MEAT_WORDS = re.compile(r"\b(chicken|beef|pork|lamb|mutton|goat|bacon|ham|sausage|sausages|turkey|duck|veal|venison|"
                        r"gelatin|gelatine|lard|suet|chorizo|pancetta|prosciutto|salami|pepperoni|mince|steak|"
                        r"stock cube|bouillon|meat)\b")


def contains_meat(key: str) -> bool:
    return bool(MEAT_WORDS.search(key))


def matches(pantry_key: str, recipe_key: str) -> bool:
    """Same ingredient if equal, or one is the other plus leading qualifiers ('basmati rice' ~ 'rice',
    'red onion' ~ 'onion'), but not 'chicken' ~ 'chicken stock'.
    ponytail: suffix heuristic; replace with a reviewed synonym table if matches look wrong."""
    if not pantry_key or not recipe_key:
        return False
    return pantry_key == recipe_key or pantry_key.endswith(" " + recipe_key) or recipe_key.endswith(" " + pantry_key)
