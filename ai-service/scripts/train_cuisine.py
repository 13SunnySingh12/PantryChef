"""Train and evaluate the cuisine classifier from the fetched sources (run scripts/fetch_sources.py first).

Writes ai-service/models/cuisine-<version>.joblib and models/cuisine.json (both git-ignored), and copies the model
card (metrics, class counts, checksums; no model weights) to ai-service/model_cards/cuisine.json for review.

Usage: python scripts/train_cuisine.py   (exit code 1 when the model fails its acceptance check, D-048)
"""

import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app import cuisine  # noqa: E402
from scripts.import_recipes import RAW, parse_all  # noqa: E402


def main():
    recipes, _ = parse_all()
    model, report = cuisine.train(recipes)
    manifest = json.loads((RAW / "fetch_manifest.json").read_text(encoding="utf-8"))
    card = cuisine.save(model, report, ROOT / "models",
                        {"fetched_at": manifest["fetched_at"], "raw_files_sha256": manifest["files"]})
    (ROOT / "model_cards").mkdir(exist_ok=True)
    shutil.copyfile(ROOT / "models" / "cuisine.json", ROOT / "model_cards" / "cuisine.json")
    test, baseline = card["test"], card["baseline_majority_class"]
    print(f"classes {len(card['data']['class_counts'])}, split {card['split']}, C={card['chosen_C']}")
    print(f"test macro-F1 {test['macro_f1']} accuracy {test['accuracy']} | majority baseline macro-F1 "
          f"{baseline['test_macro_f1']} accuracy {baseline['test_accuracy']}")
    check = card["acceptance"]
    cv = check["cross_validation"]
    print(f"{check['metric']} ({check['estimate']}): {check['observed']} (sd {cv['model']['sd']}) vs pass mark "
          f"{check['threshold']} [{check['status']}] | reference baseline {cv['reference_baseline_complement_nb']['mean']}")
    print("PASS" if check["passed"] else "FAIL: the service will refuse this model until a retrained one passes")
    return 0 if check["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
