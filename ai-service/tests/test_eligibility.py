import json
from pathlib import Path

import pytest

from app.eligibility import reasons

FIXTURE = Path(__file__).resolve().parents[2] / "database" / "fixtures" / "eligibility_cases.json"
CASES = json.loads(FIXTURE.read_text(encoding="utf-8"))["cases"]


@pytest.mark.parametrize("case", CASES, ids=[c["name"] for c in CASES])
def test_matches_shared_fixture(case):
    recipe = case["recipe"]
    assert (
        reasons(
            recipe["dietLabels"],
            recipe["allergenMetadataComplete"],
            recipe["ingredientAllergens"],
            case["diets"],
            case["allergens"],
        )
        == case["reasons"]
    )


def test_unknown_diet_code_is_never_met():
    assert reasons(["vegan"], True, [], ["keto"], []) == ["diet_not_met:keto"]
