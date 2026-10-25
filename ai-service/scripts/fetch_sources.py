"""Download the approved recipe sources into ai-service/data/raw/ (git-ignored).

Wikibooks Cookbook (CC BY-SA 4.0) via the MediaWiki API, and TheMealDB via its official JSON API with the
development/educational test key. Raw files are kept out of git; fetch_manifest.json records what was fetched,
when, from where, under which license, with SHA-256 checksums. Re-running overwrites the cache.

Usage: python scripts/fetch_sources.py
"""

import hashlib
import json
import string
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

RAW = Path(__file__).resolve().parents[1] / "data" / "raw"
USER_AGENT = "PantryChef-importer/0.1 (https://github.com/13SunnySingh12/PantryChef)"
WIKIBOOKS_API = "https://en.wikibooks.org/w/api.php"
MEALDB_API = "https://www.themealdb.com/api/json/v1/1"
DELAY_SECONDS = 0.5


def get_json(url, params=None):
    if params:
        url += "?" + urllib.parse.urlencode(params)
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        data = json.load(response)
    time.sleep(DELAY_SECONDS)
    return data


def wiki(params):
    return get_json(WIKIBOOKS_API, {"format": "json", "formatversion": "2", "maxlag": "5", **params})


def wikibooks_cuisines():
    """Map each 'Recipes by origin' category (and its sub-categories, 3 levels) to a cuisine name."""
    cuisines, frontier = {}, ["Category:Recipes by origin"]
    for _ in range(3):
        next_frontier = []
        for category in frontier:
            members = wiki({"action": "query", "list": "categorymembers", "cmtitle": category,
                            "cmtype": "subcat", "cmlimit": "max"})["query"]["categorymembers"]
            for member in members:
                title = member["title"]
                if title.endswith(" recipes") and title not in cuisines:
                    cuisines[title] = title.removeprefix("Category:").removesuffix(" recipes")
                    next_frontier.append(title)
        frontier = next_frontier
    return cuisines


def wikibooks_pages():
    """All Cookbook pages in Category:Recipes with current wikitext, revision id and visible categories."""
    pages, params = {}, {"action": "query", "generator": "categorymembers", "gcmtitle": "Category:Recipes",
                         "gcmnamespace": "102", "gcmlimit": "50", "prop": "revisions|categories",
                         "rvprop": "ids|content", "rvslots": "main", "cllimit": "max", "clshow": "!hidden"}
    cont = {}
    while True:
        data = wiki({**params, **cont})
        for page in data.get("query", {}).get("pages", []):
            entry = pages.setdefault(page["pageid"], {"pageid": page["pageid"], "title": page["title"],
                                                      "categories": [], "revid": None, "wikitext": None})
            entry["categories"] += [c["title"] for c in page.get("categories", [])]
            if page.get("revisions"):
                revision = page["revisions"][0]
                entry["revid"] = revision["revid"]
                entry["wikitext"] = revision["slots"]["main"]["content"]
        if "continue" not in data:
            return list(pages.values())
        cont = data["continue"]
        print(f"  wikibooks: {len(pages)} pages so far", flush=True)


def mealdb_meals():
    meals = {}
    for letter in string.ascii_lowercase + string.digits:
        for meal in get_json(f"{MEALDB_API}/search.php", {"f": letter}).get("meals") or []:
            meals[meal["idMeal"]] = meal
    return list(meals.values())


def write_jsonl(path, rows):
    path.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    RAW.mkdir(parents=True, exist_ok=True)
    fetched_at = datetime.now(timezone.utc).isoformat(timespec="seconds")
    print("Fetching Wikibooks cuisine categories...", flush=True)
    (RAW / "wikibooks_cuisines.json").write_text(json.dumps(wikibooks_cuisines(), indent=1, ensure_ascii=False),
                                                 encoding="utf-8")
    print("Fetching Wikibooks recipe pages...", flush=True)
    pages = [p for p in wikibooks_pages() if p["wikitext"]]
    write_jsonl(RAW / "wikibooks_pages.jsonl", pages)
    print("Fetching TheMealDB meals...", flush=True)
    meals = mealdb_meals()
    write_jsonl(RAW / "themealdb_meals.jsonl", meals)
    files = ["wikibooks_cuisines.json", "wikibooks_pages.jsonl", "themealdb_meals.jsonl"]
    manifest = {
        "fetched_at": fetched_at,
        "sources": {
            "wikibooks": {"api": WIKIBOOKS_API, "license": "CC BY-SA 4.0",
                          "license_url": "https://creativecommons.org/licenses/by-sa/4.0/", "pages": len(pages)},
            "themealdb": {"api": MEALDB_API, "terms": "https://www.themealdb.com/terms_of_use.php",
                          "key": "public test key (development/educational use)", "meals": len(meals)},
        },
        "files": {name: sha256(RAW / name) for name in files},
    }
    (RAW / "fetch_manifest.json").write_text(json.dumps(manifest, indent=1), encoding="utf-8")
    print(json.dumps(manifest["sources"], indent=1))


if __name__ == "__main__":
    main()
