"""Whether a recipe may be suggested or planned for a user (FR-011, FR-012, A-08).

Fails closed. Mirrors backend Eligibility.java; both are tested against
database/fixtures/eligibility_cases.json, so change them together.
"""

from collections.abc import Collection

NOT_VEGAN = frozenset({"dairy", "eggs", "fish", "shellfish"})
NOT_VEGETARIAN = frozenset({"fish", "shellfish"})


def reasons(
    diet_labels: Collection[str],
    allergen_metadata_complete: bool,
    ingredient_allergens: Collection[str],
    diets: Collection[str],
    allergens: Collection[str],
) -> list[str]:
    """Reasons the recipe is not allowed, in a stable order; empty means eligible."""
    labels, flags = set(diet_labels), set(ingredient_allergens)
    out = []
    if allergens and not allergen_metadata_complete:
        out.append("allergen_data_incomplete")
    out += [f"allergen_present:{a}" for a in sorted(set(allergens)) if a in flags]
    out += [f"diet_not_met:{d}" for d in sorted(set(diets)) if not _meets(d, labels, flags, allergen_metadata_complete)]
    return out


def _meets(diet: str, labels: set[str], flags: set[str], complete: bool) -> bool:
    if diet == "vegan":
        return "vegan" in labels and not flags & NOT_VEGAN
    if diet == "vegetarian":
        return bool(labels & {"vegetarian", "vegan"}) and not flags & NOT_VEGETARIAN
    if diet == "gluten_free":
        return "gluten" not in flags and ("gluten_free" in labels or complete)
    return False
