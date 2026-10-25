# PantryChef — Recipe Data and Embedding Model Options (B-01, B-02)

Researched 2026-10-25 from official sources (links at the end). This is not legal advice; where a term was not found it says "not stated". Nothing has been downloaded or ingested.

## 1. Recipe datasets

| Source | License / terms (as published) | Size | Fields useful to PantryChef | Missing | Verdict |
|---|---|---|---|---|---|
| **Wikibooks Cookbook** | Page footer: "Creative Commons Attribution-ShareAlike License" (CC BY-SA 4.0). Reuse, modification and commercial use allowed **if** we credit the page/authors with a link, link the license, note changes, and share adapted recipe text under CC BY-SA. | ≈3,832 pages in `Category:Recipes` (MediaWiki API count) | Ingredient bullet list with quantities in text, each ingredient linked to a canonical Cookbook page (good normalization key); `servings` in the recipe template; diet categories (Vegan 260, Vegetarian 394, Gluten-free 97 pages); cuisine categories (Indian 149, Italian 123, Mexican 50, …); per-page URL for citations | Nutrition, allergen data, consistent quantity format; quality varies by page | **Recommended** — the only candidate with a clear, rights-holder-granted reuse license |
| **TheMealDB API** | Terms: "You can scrape, copy and modify any content returned from the API, as long as you use the official end points"; test key "1" is for "development of your app or for educational use"; publishing to an app store needs a paid supporter key; credit TheMealDB as the data source; do not resell the API. Ownership of recipe text not stated. | ≥ 793 meals (sum of category lists; free key caps lists at 100, so likely more) | `strArea` (cuisine/country, 195 values), `strCategory` (incl. Vegetarian, Vegan), `strIngredient1–20` + `strMeasure1–20` (free-text measures), instructions, `strSource` URL | Nutrition, allergens, servings; area labels inconsistent (list says "Indian", a record says "India"; `filter?a=Indian` returned 0); one category per meal, so most vegetarian dishes aren't labelled Vegetarian | **Acceptable for local/educational use**, weaker labels; their own guide warns not to infer diet/allergy safety from category or tags |
| **RecipeNLG** | Dataset card: "Researcher shall use the Dataset only for non-commercial research and educational purposes"; download via the project site after a permission request. Recipes were gathered from third-party websites. | 2,231,142 recipes (≈1.6M "Gathered") | title, ingredient strings with quantities, directions, source link, NER ingredient names | Cuisine, diet, allergen, nutrition labels, servings | **Not recommended**: non-commercial research only; far too large for the Supabase free 500 MB DB; no labels for the classifier or filters |
| Food.com (Kaggle: shuyangli94; irkaal "CC0") | Kaggle shows "Data files © Original Authors" / an uploader-chosen "CC0". Food.com's owner's Visitor Agreement: "you may not otherwise reproduce any of the materials without the prior written consent of the owner" and "You may not distribute copies of materials". | 180K / 522K recipes | Rich: quantities, nutrition, servings, keywords | — | **Rejected**: the rights holder does not permit reproduction; an uploader's CC0 label cannot grant rights it doesn't hold |
| Epicurious (Kaggle hugodarwood) | Kaggle license "Unknown"; scraped from a publisher site | not checked | nutrition, categories | — | **Rejected**: no license grant |
| Recipe Ingredients / "What's Cooking" (Kaggle) | Kaggle license "Unknown" (competition data) | not checked | cuisine label + ingredient names only | — | **Rejected**: no license grant |
| Indian Food 101 (Kaggle) | "Data files © Original Authors"; acknowledges Wikipedia and two recipe blogs | not checked | diet (veg/non-veg), region | — | **Rejected**: no license grant |

## 2. Nutrition source

| Source | License | Notes | Verdict |
|---|---|---|---|
| **USDA FoodData Central** | "published under CC0 1.0 Universal" (public domain); citation requested, not required | API needs a free data.gov key (DEMO_KEY: 30 req/hour, 50/day); per-food energy, protein, carbohydrate, fat | **Recommended** for deriving nutrition, because none of the usable recipe sources ship nutrition. Derivation needs ingredient→food matching and unit→gram conversion; anything unmatched stays "unknown" (FR-021) |

## 3. Embedding models (Hugging Face model API + official configs)

| Model | License | Dim | Weights | Notes | Verdict |
|---|---|---|---|---|---|
| **sentence-transformers/all-MiniLM-L6-v2** | Apache-2.0 | 384 | 91 MB | 6 layers, max 256 tokens; not gated; most-downloaded | **Recommended**: smallest, CPU-friendly, enough for short recipe chunks |
| BAAI/bge-small-en-v1.5 | MIT | 384 | 133 MB | 12 layers; often better retrieval quality; query instruction prefix | Good alternative |
| intfloat/e5-small-v2 | MIT | 384 | 133 MB | needs `query:` / `passage:` prefixes | Alternative |
| sentence-transformers/all-mpnet-base-v2 | Apache-2.0 | 768 | 438 MB | slower on CPU, 2× vector storage | Only if quality is lacking |
| nomic-ai/nomic-embed-text-v1.5 | Apache-2.0 | 768 | — | requires `trust_remote_code` (runs repo code) | Not recommended |
| google/embeddinggemma-300m | Gemma terms, gated (manual approval) | — | — | extra license terms + account approval | Rejected |

Storage check: ≈3,800 recipes × 384 floats ≈ 6 MB of vectors — well inside the Supabase free tier.

## 4. Consequences you should decide with the dataset

1. **Allergen data does not exist in any usable source.** Under the approved fail-closed rule (A-08/D-028), a user with any allergen exclusion would see **zero** recipes. The workable option: an ingredient→allergen dictionary (reviewed, versioned, committed) applied to each recipe's normalized ingredients; a recipe gets `allergen_metadata_complete = true` only when *every* ingredient is mapped. Unmapped ingredients keep it unknown (still excluded). This needs your approval because it is a derivation, not source data.
2. **Diet labels:** Wikibooks diet categories become source-backed `diet_labels`; recipes without a category stay unlabelled (excluded for that diet), except `gluten_free` which the rule can derive from complete allergen data.
3. **Cuisine classifier labels:** Wikibooks cuisine categories (and/or normalized TheMealDB `strArea`) provide real labels; classes with too few recipes are merged into "other" or dropped, decided from the actual counts.
4. **Share-alike:** with Wikibooks, recipe text stored/displayed must keep the page link, author credit (page history link), the CC BY-SA 4.0 link and a "modified" note; the raw dump is kept out of git (D-001 `.gitignore`), the import manifest records URL, revision ID and license per recipe.

## 5. Recommendation

Wikibooks Cookbook (CC BY-SA 4.0) for recipes, citations, diet and cuisine labels, plus USDA FoodData Central (CC0) for derived nutrition, plus `all-MiniLM-L6-v2` (Apache-2.0, 384-d) for embeddings, plus an approved allergen dictionary. Optionally add TheMealDB (development/educational key) as a second source if more variety is wanted, with its credit line.

## Sources

- Wikibooks Cookbook license footer: https://en.wikibooks.org/wiki/Cookbook:Table_of_Contents ; counts via https://en.wikibooks.org/w/api.php (categoryinfo); CC BY-SA 4.0: https://creativecommons.org/licenses/by-sa/4.0/
- TheMealDB API page: https://www.themealdb.com/api.php ; terms: https://www.themealdb.com/terms_of_use.php ; agent guide: https://www.themealdb.com/AGENTS.md ; counts via the official JSON API with the test key
- RecipeNLG: https://huggingface.co/datasets/mbien/recipe_nlg ; https://github.com/Glorf/recipenlg ; https://recipenlg.cs.put.poznan.pl/
- Kaggle dataset metadata: https://www.kaggle.com/api/v1/datasets/view/{owner}/{dataset} for shuyangli94/food-com-recipes-and-user-interactions, irkaal/foodcom-recipes-and-reviews, hugodarwood/epirecipes, kaggle/recipe-ingredients-dataset, nehaprabhavalkar/indian-food-101
- Food.com owner's Visitor Agreement (linked from food.com footer): https://www.wbd.com/discovery-visitor-agreement/
- USDA FoodData Central API guide: https://fdc.nal.usda.gov/api-guide/
- Model metadata: https://huggingface.co/api/models/{id} and `config.json` for sentence-transformers/all-MiniLM-L6-v2, BAAI/bge-small-en-v1.5, intfloat/e5-small-v2, sentence-transformers/all-mpnet-base-v2, nomic-ai/nomic-embed-text-v1.5, google/embeddinggemma-300m
