"""Grocery arithmetic (FR-017, QA-012) on hand-written needs, no database."""

from app.grocery import Stock, compute


def item(result, key):
    return next(i for i in result["items"] if i["ingredient"] == key)


def test_compatible_units_are_aggregated_into_one_family():
    result = compute([("rice", 500, "g"), ("rice", 1, "kg"), ("milk", 1, "l"), ("milk", 250, "ml"),
                      ("egg", 1, "dozen"), ("egg", 2, "piece")], [])
    assert item(result, "rice") == {"ingredient": "rice", "quantity": 1500.0, "unit": "g", "unresolved": False,
                                    "note": None}
    assert (item(result, "milk")["quantity"], item(result, "milk")["unit"]) == (1250.0, "ml")
    assert (item(result, "egg")["quantity"], item(result, "egg")["unit"]) == (14.0, "piece")


def test_pantry_stock_lower_equal_and_higher_than_need():
    needs = [("rice", 1000, "g"), ("onion", 3, "piece"), ("milk", 500, "ml")]
    pantry = [Stock("Basmati rice", 0.4, "kg"), Stock("Onion", 3, "piece"), Stock("Milk", 2, "l")]
    result = compute(needs, pantry)
    assert (item(result, "rice")["quantity"], item(result, "rice")["unit"]) == (600.0, "g")
    assert sorted(result["coveredByPantry"]) == ["milk", "onion"]
    assert [i["ingredient"] for i in result["items"]] == ["rice"]


def test_incompatible_or_unknown_quantities_stay_unresolved():
    needs = [("spinach", 200, "g"), ("salt", None, None), ("flour", 200, "g"), ("flour", 1, "pack")]
    pantry = [Stock("Spinach", 1, "bunch")]
    result = compute(needs, pantry)
    assert item(result, "spinach") == {"ingredient": "spinach", "quantity": None, "unit": None, "unresolved": True,
                                       "note": "pantry stock is in a different unit"}
    assert item(result, "salt")["unresolved"] and item(result, "salt")["quantity"] is None
    assert item(result, "flour")["unresolved"]


def test_one_pantry_item_is_not_counted_twice():
    # 500 g of rice in the pantry matches both "rice" and "basmati rice"; it may only be spent once.
    result = compute([("basmati rice", 400, "g"), ("rice", 400, "g")], [Stock("Basmati rice", 500, "g")])
    assert result["coveredByPantry"] == ["basmati rice"]
    assert (item(result, "rice")["quantity"], item(result, "rice")["unit"]) == (300.0, "g")


def test_empty_plan_needs_nothing():
    assert compute([], [Stock("Rice", 1, "kg")]) == {"items": [], "coveredByPantry": []}
