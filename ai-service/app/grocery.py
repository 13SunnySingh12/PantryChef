"""Grocery list = what the planned recipes need minus usable pantry stock (FR-017, A-04).

Only dimensionally valid conversions inside one family are used: g/kg, ml/l, piece/dozen; bunch and pack stand
alone. Anything else is listed as unresolved instead of being guessed: an unknown recipe quantity, recipe amounts
in two different families, or pantry stock that only exists in an incompatible unit. Pantry stock is consumed as it
is matched, so one pantry item never covers two different needs.
"""

from collections import defaultdict
from collections.abc import Iterable, Sequence
from dataclasses import dataclass

from app.ingredients import matches, normalize

BASE_UNITS = {"g": ("g", 1), "kg": ("g", 1000), "ml": ("ml", 1), "l": ("ml", 1000), "piece": ("piece", 1),
              "dozen": ("piece", 12), "bunch": ("bunch", 1), "pack": ("pack", 1)}


@dataclass(frozen=True)
class Stock:
    name: str
    quantity: float
    unit: str


def compute(needs: Iterable[tuple[str, float | None, str | None]], pantry: Sequence[Stock]) -> dict:
    """needs: (normalized ingredient, quantity, unit) rows from the planned recipes."""
    totals: dict[str, dict[str, float]] = defaultdict(lambda: defaultdict(float))
    unknown: set[str] = set()
    for key, quantity, unit in needs:
        if not key:
            continue
        if quantity is None or unit not in BASE_UNITS:
            unknown.add(key)
            totals.setdefault(key, defaultdict(float))
            continue
        base, factor = BASE_UNITS[unit]
        totals[key][base] += float(quantity) * factor

    stock = []  # [pantry key, base unit, remaining amount]
    for item in pantry:
        if item.unit in BASE_UNITS and item.quantity > 0:
            base, factor = BASE_UNITS[item.unit]
            stock.append([normalize(item.name), base, float(item.quantity) * factor])

    items, covered = [], []
    for key in sorted(totals):
        matching = [s for s in stock if matches(s[0], key)]
        amounts = totals[key]
        if key in unknown or len(amounts) != 1:
            items.append(_unresolved(key, "quantity or unit not convertible"))
            continue
        (base, needed), = amounts.items()
        same_unit = [s for s in matching if s[1] == base]
        if matching and not same_unit:
            items.append(_unresolved(key, "pantry stock is in a different unit"))
            continue
        for s in same_unit:
            used = min(s[2], needed)
            s[2] -= used
            needed -= used
        if needed <= 1e-9:
            covered.append(key)
        else:
            items.append({"ingredient": key, "quantity": round(needed, 3), "unit": base, "unresolved": False,
                          "note": None})
    return {"items": items, "coveredByPantry": covered}


def _unresolved(key: str, note: str) -> dict:
    return {"ingredient": key, "quantity": None, "unit": None, "unresolved": True, "note": note}
