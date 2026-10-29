"""Parse the fetched sources, import them into the database and embed them (idempotent; safe to re-run).

Needs INGEST_DATABASE_URL (owner role, e.g. postgresql://pantrychef:<password>@127.0.0.1:5440/pantrychef).
Writes ai-service/data/import_report.json (git-ignored) and database/imports/<date>-summary.json (counts and
checksums only, no recipe text, committed as provenance; not written by --dry-run).

Usage: python scripts/import_recipes.py [--dry-run]
"""

import json
import sys
from collections import Counter
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import psycopg  # noqa: E402

from app.config import EMBEDDING_MODEL_ID, setting  # noqa: E402
from app.importer import import_recipes  # noqa: E402
from app.ingredients import dictionary  # noqa: E402
from app.sources import parse_mealdb, parse_wikibooks  # noqa: E402

RAW = ROOT / "data" / "raw"


def read_jsonl(name):
    # split on "\n" only: recipe text can contain U+2028, which str.splitlines() would also split on
    return [json.loads(line) for line in (RAW / name).read_text(encoding="utf-8").split("\n") if line]


def parse_all():
    cuisines = json.loads((RAW / "wikibooks_cuisines.json").read_text(encoding="utf-8"))
    recipes, report = [], {}
    for source, rows, parse in (("wikibooks", read_jsonl("wikibooks_pages.jsonl"), lambda p: parse_wikibooks(p, cuisines)),
                                ("themealdb", read_jsonl("themealdb_meals.jsonl"), parse_mealdb)):
        parsed, skipped = [], Counter()
        for row in rows:
            recipe, reason = parse(row)
            if recipe:
                parsed.append(recipe)
            else:
                skipped[reason] += 1
        ingredients = [i for r in parsed for i in r["ingredients"]]
        report[source] = {
            "fetched": len(rows),
            "imported": len(parsed),
            "skipped": dict(skipped),
            "allergen_complete": sum(r["allergen_metadata_complete"] for r in parsed),
            "diet_labels": dict(Counter(label for r in parsed for label in r["diet_labels"])),
            "with_cuisine": sum(1 for r in parsed if r["cuisine"]),
            "ingredients": len(ingredients),
            "ingredients_with_resolved_quantity": sum(1 for i in ingredients if i["quantity"]),
            "top_unknown_allergen_ingredients": Counter(i["normalized"] for i in ingredients
                                                        if not i["known"]).most_common(25),
        }
        recipes += parsed
    return recipes, report


def main():
    recipes, report = parse_all()
    manifest = json.loads((RAW / "fetch_manifest.json").read_text(encoding="utf-8"))
    summary = {"date": date.today().isoformat(), "fetched_at": manifest["fetched_at"],
               "raw_files_sha256": manifest["files"], "embedding_model": EMBEDDING_MODEL_ID,
               "allergen_dictionary": dictionary()["version"], "sources": report}
    if "--dry-run" not in sys.argv:
        url = setting("INGEST_DATABASE_URL")
        if not url:
            sys.exit("Set INGEST_DATABASE_URL (owner role) in the environment or .env")
        from app.embeddings import embed

        with psycopg.connect(url) as conn:
            summary["database"] = import_recipes(conn, recipes, embed)
    (ROOT / "data").mkdir(exist_ok=True)
    (ROOT / "data" / "import_report.json").write_text(json.dumps(summary, indent=1, ensure_ascii=False), encoding="utf-8")
    if "database" in summary:  # only real imports leave a committed provenance record
        out = ROOT.parent / "database" / "imports" / f"{summary['date']}-summary.json"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(summary, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({k: {x: y for x, y in v.items() if x != "top_unknown_allergen_ingredients"}
                      for k, v in report.items()}, indent=1))
    print("database:", summary.get("database", "dry run"))


if __name__ == "__main__":
    main()
