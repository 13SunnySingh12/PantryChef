# PantryChef — Progress Log

Legend: `[✓]` completed and verified · `[→]` in progress · `[ ]` pending · `[!]` manual action required · `[✗]` failed/blocked

## Current state (2026-10-29)

**All planned loops (L1–L13) complete and verified; D-031, A-10 and TBD-027 decided on 2026-10-29 (see below). Remaining: the cuisine pass mark is provisional (D-048); the waste-risk model is blocked (B-03). See [RELEASE_READINESS.md](RELEASE_READINESS.md).**

### Decisions D-031, A-10, TBD-027 (2026-10-29) `[✓]`

- D-031: allergen dictionary 2026-10-29.1 approved (accent folding, 10 synonyms, may-contain flags, composites unknown, dead entries removed)
- A-10: optional `startDate` (today … today + 7, APP_TIMEZONE), default today; plan and grocery use the pantry as of the start date; frontend date field
- TBD-027 / D-048: provisional pass mark 0.56 on cross-validated macro-F1; model 0.5872 ± 0.0303 → PASS; enforced at load
- Committed locally only (not pushed, per the instruction for this task)

| Check | Result |
|---|---|
| `python -m pytest -q` | 152 passed (new: synonyms, false-match, invariant and unknown-ingredient tests; acceptance and shuffled-label tests) |
| `mvnw.cmd -B verify` | BUILD SUCCESS, 92 tests (new: start-date integration test, `PlanStartDateTests` incl. the UTC/IST date boundary) |
| `npm run lint` / `npx vitest run` / `npm run build` | clean / 14 passed (new: start-date test) / built |
| Mutations | accent folding off, a dead entry, `oil` allergen-free, pantry not moved to the start date (generation and recalculation), past-date check off, start date never sent, load gate off: each failed its test → restored |
| Import dry run | 4,067 recipes; allergen-complete 722 → 728; no non-ASCII keys left |
| Retrained classifier | C = 10; CV macro-F1 0.5872 ± 0.0303 (25 folds, 0.535–0.632); reference baseline 0.4981; held-out 0.5326, accuracy 0.6032 |

Also fixed: the dashboard test's lazy-loaded chart chunk is now loaded before the timed wait, the likely cause of the one-off frontend failure in Loop 13 (failed on a cold cache, passes after the fix with the cache cleared).

Follow-up: recipes imported with an older dictionary version now count as allergen-unknown until re-imported (retrieval and planner check `allergen_source`; startup warning with the count; tests `test_recipes_from_an_older_dictionary_*` in retrieval and planner, mutation-checked). No PantryChef database exists on this machine, so there was nothing to re-import here; a fresh import always uses the current dictionary. AI suite 154 passed.

Not covered: the A-10 date field was checked by tests, not in the browser.

### Loop 13 — Security, clean setup, performance, final review (2026-10-29) `[✓]`

- Security review (QA-019–023, ROAD-024): SQL only joins constant fragments, every value is bound; recipe links are always built as `https://` Wikibooks/TheMealDB URLs; existing tests already covered injection, XSS, token and ownership cases, internal-service auth and the read-only AI role
- Added: request-body cap (413 over 64 KB, 411 without a length) and a closed-CORS test (D-047); frontend journey test (register → add item → expiring → plan → grocery check-off) against a stateful fake backend
- Fixed: local service URLs use `127.0.0.1` (D-046)
- Ponytail audit: removed the unused `hasToken()`; nothing else worth cutting (about 5,300 lines across three services)
- Commits `b1dc430`, `c303cdb`, `002bbc7`; CI run [40824013913](https://github.com/13SunnySingh12/PantryChef/actions/runs/40824013913) on `002bbc7`: **success**

| Check | Result |
|---|---|
| Clean clone from GitHub (`24823a9`), module checks | npm ci 39 s, lint, test, build, audit; `mvnw verify` 75 s; venv + pip install 406 s, pytest 54 s, compileall: all exit 0 |
| Clean clone, full stack from the README | DB on a separate port; backend started in 8.2 s and applied 3 migrations; sources fetched (357 s), 4,068 recipes imported and embedded (251 s; 4,067 in Loop 7, one source changed); cuisine retrained (22 s) to the same test macro-F1 0.5156 |
| Size cap and CORS tests | `AuthTests` 2 new tests; mutations (cap → `Long.MAX_VALUE`, 411 branch off, allow-all CORS) each failed their test → restored |
| Journey test | passes; mutation (pantry list not reloaded after adding) fails it. First version shared arrays with the page, which let that mutation pass; fixed by returning copies |
| Regression | backend 89 tests BUILD SUCCESS; frontend 13 tests (one unexplained failure in a run alongside the backend build and recipe import, then 4 clean reruns); lint clean |
| Secrets (QA-023) | no secrets in tracked files, all history or the last 12 CI logs; `.env` ignored; no local `.env` exists; clean-run logs: none of the 4 throwaway secrets, no tokens, no password lines |

Performance baseline (same laptop, other projects running; 10 pantry items; median of n runs):

| Workflow | n | before (`localhost`) | after (`127.0.0.1`) | p95 after |
|---|---|---|---|---|
| First recipe search after start (model load) | 1 | 5,652 ms | 395 ms | — |
| Recipe search (warm) | 10 | 5,252 ms | 219 ms | 362 ms |
| Generate 7-day plan (21/21 slots, 53 grocery lines) | 5 | 5,536 ms | 488 ms | 632 ms |
| Grocery recalculation | 5 | 5,170 ms | 141 ms | 166 ms |
| Login (BCrypt) | 10 | 117 ms | 112 ms | 131 ms |
| Pantry list / alerts / plan view / dashboard | 20 each | 7–11 ms | 7–12 ms | 31–34 ms |

Cause of the old numbers: `localhost` → `::1` first; refused IPv6 connects cost about 2 s each on Windows (direct test: psycopg via `localhost` 10,038 ms, via `127.0.0.1` 15 ms).

Not covered: no automated browser test against the real stack; the frontend flake was not reproduced, so its cause is unknown; stopping the services once ended more of this session's own processes than intended (wrapper shells only, nothing of yours).

### Loop 12 — CI hardening (2026-10-29) `[✓]`

- Backend job gains a dependency scan: Maven CycloneDX SBOM → OSV-Scanner v2.7.0, release checksum verified (D-045, resolves TBD-022)
- Temporary PR 13SunnySingh12/PantryChef#1 from `ci/deliberate-failure`, closed without merging

| Check | Result |
|---|---|
| First scanner attempt (`pom.xml`), run [40819269353](https://github.com/13SunnySingh12/PantryChef/actions/runs/40819269353) | passed but **checked nothing**: 13 packages found, all 13 filtered as unscannable (versions come from the Spring Boot BOM) → replaced |
| SBOM scan on `main`, run [40819606793](https://github.com/13SunnySingh12/PantryChef/actions/runs/40819606793) on `1ea5c95` | **success**; 71 resolved packages scanned, no known vulnerabilities; frontend, backend and ai-service all success |
| PR phase 1: a failing test in each module, run [40819841417](https://github.com/13SunnySingh12/PantryChef/actions/runs/40819841417) (`pull_request`) | **failure** as intended: frontend 1 failed / 12 passed, backend only `DeliberateFailureTests` failed, ai-service 1 failed / 114 passed |
| PR phase 2: commons-collections 3.2.1 added, run [40820080227](https://github.com/13SunnySingh12/PantryChef/actions/runs/40820080227) | **failure** as intended, only the backend dependency scan: 72 packages, GHSA-fjq5-5j5f-mvxh (CVSS 9.8) and GHSA-6hgm-866r-3cjv |
| PR phase 3: dependency removed, run [40820280614](https://github.com/13SunnySingh12/PantryChef/actions/runs/40820280614) | **success**; branch content identical to `main` |
| Workflow review | no deploy, publish or release step; `permissions: contents: read`; no secrets used |
| Regression gate | no application code changed since the Loop 11 gate (only a test-file rename); all three suites passed on `main` in run 40819606793; full-stack startup last verified in the Loop 11 browser run |

Not covered: running OSV-Scanner locally (a downloaded binary was blocked on this machine, so the scan runs only in CI); test-scope Java dependencies are not scanned; the `ci/deliberate-failure` branch still exists on GitHub (delete it only if you want).

### Loop 11 — Frontend (2026-10-29) `[✓]`

- Skills used: `design-taste-frontend` (general rules only; it excludes product UI), `emil-design-eng` (motion restraint, press feedback), Motion docs via the Motion MCP, `impeccable` (context loader, craft floor, audit checks, detector)
- Screens: sign in / register, pantry (add/edit/remove, use/finish/throw away), expiring soon, recipes (citations, disclaimer, cuisine estimate), meal plan (21 slots with reasons, grocery check-off and recalculation), dashboard (totals, line chart, nutrition), diet and allergies (D-044)
- Backend: plain-language validation messages on every DTO and cross-field check

| Check | Result |
|---|---|
| `npm run lint` (oxlint, deny warnings) / `npm run build` | pass; dashboard chunk lazy-loaded |
| `npx vitest run` | 12 passed (sign-in errors, session expiry, pantry validation + retry key, recipe citation + XSS-as-text, empty reasons, plan gaps, failed check-off rollback, dashboard unknown nutrition, preferences) |
| Mutation: new request key per retry | retry test failed → restored |
| `mvnw.cmd -B verify` | BUILD SUCCESS, 87 tests (after message changes) |
| Real browser (built-in pane), full stack with 4,067 imported recipes | sign-up validation and success; add item; pantry, expiring, recipes (~8 s first search), plan, grocery, dashboard, preferences at 1280×800, 768×1024 and 375×812, dark and light |
| Found in the browser and fixed | stretched mobile navigation on short pages; 32 px touch targets; underlined link-button; tablespoon amounts shown as 14.787 ml; "1 items"; framework validation wording ("size must be between 8 and 72") |
| Impeccable detector | no findings (static scan of `frontend/src`, live scan of the login page) |
| Contrast (computed) | every text/status token pair ≥ 5.09:1 in light and dark |
| Logs | 0 secrets in backend/AI logs from the browser session |
| Regression gate (all three suites) | frontend 12, backend 87, ai-service 114 passed |

Commits `b0be1ef` (backend messages), `63b7bd9` (web app), `4c2bebf` (docs), pushed. CI run [40818872193](https://github.com/13SunnySingh12/PantryChef/actions/runs/40818872193) on `4c2bebf`: **success** (frontend, backend, ai-service). Follow-up commit fixes the test file name's case in git (`App.test.jsx` → `app.test.jsx`; Windows had kept the old casing).

Not covered: the detector could only load the sign-in page (other pages need a signed-in session); first recipe search took about 8 s (measure in L13); no PRODUCT.md (offer `/impeccable init`).

### Loop 10 — Cuisine classifier + waste predictor (2026-10-29) `[✓]`

- User decided (2026-10-29): leave nutrition unknown (D-040)
- `app/cuisine.py`, `scripts/train_cuisine.py`, model card `ai-service/model_cards/cuisine.json` (D-041); internal `POST /internal/v1/cuisine/classify`, `POST /internal/v1/waste/predict` (D-042)
- Backend `recipes/EstimatesController`: `POST /api/v1/cuisine/classify`, `GET /api/v1/pantry-items/{id}/waste-risk` (D-043)

| Check | Result |
|---|---|
| Training on real labels | 1,256 recipes, 18 classes, split 879/188/189, C=3.0; **test macro-F1 0.5156, accuracy 0.5926**; majority baseline 0.0157 / 0.1640; best Indian F1 0.85, Japanese 0.80, Nigerian 0.76; worst Polish 0.22, Filipino 0.00 (test classes have 4–31 recipes, so per-class figures are rough) |
| `python -m pytest -q` | 114 passed (new: test_cuisine 6) |
| `mvnw.cmd -B verify` | BUILD SUCCESS — 87 tests (new: EstimatesTests 2) |
| Mutation: model hash check removed | tampered-model test failed → restored |
| Real end-to-end (DB + AI service + backend + real model) | paneer/spinach/garam masala/ghee → Indian; unknown ingredient → `unknown`; waste-risk → `insufficient_data`; 0 secrets in logs |

Commits `d2cf5ed`, `a88eb84`, `50e25c1`, pushed (first `git push` attempt exited 1 with no change; the retry succeeded). CI run [40782691823](https://github.com/13SunnySingh12/PantryChef/actions/runs/40782691823): **success** (all three jobs).


### Loop 9 — Dashboard: food saved, money saved, nutrition, chart data (2026-10-29) `[✓]`

- `dashboard/DashboardService` + `DashboardController`: `GET /api/v1/dashboard/summary` (D-039)

| Check | Result |
|---|---|
| `mvnw.cmd -B verify` | BUILD SUCCESS — 85 tests (new: DashboardTests 6) |
| Mutations | use after expiry counted as saved → failed; zero instead of unknown nutrition → failed (both restored) |
| Real HTTP run (compose DB + jar) | 200 g of 400 g paneer bought for ₹180, used before expiry → ₹90 saved; 1 bunch spinach thrown away reported separately; 7-day daily series; nutrition `null` with no plan |
| Nutrition feasibility (real data) | 88 of 1,270 plannable recipes state servings and have every amount → at most ~7% derivable from USDA FDC (D-040, open) |

Commits `908b61f` (feat), `9d0151f` (docs), pushed. CI run [40780592513](https://github.com/13SunnySingh12/PantryChef/actions/runs/40780592513): **success** (all three jobs).

### Loop 8b — Approved unit conversions (2026-10-29) `[✓]`

- User approved converting oz/lb → g and US tsp/tbsp/cup → ml at import (D-038); `app/ingredients.py` `CONVERTED_UNITS`
- Tests: 108 Python tests pass (new conversion cases incl. fluid ounces staying unresolved)
- Real data (dry-run parse of the cached downloads): resolved quantities Wikibooks 6,513 → 19,027 of 25,817 (73%), TheMealDB 3,658 → 6,768 of 8,145 (83%); a sample 21-recipe grocery list has 52 of 130 items unresolved (mostly the same ingredient given in volume in one recipe and weight in another)
- Commits `8dbf882`, `c458de0`, pushed. CI run [40778891986](https://github.com/13SunnySingh12/PantryChef/actions/runs/40778891986): **success** (all three jobs)
- Not re-run: a full database re-import + end-to-end plan (the parser change only affects ingredient rows; the importer replaces them on the next run)

### Loop 8 — Seven-day meal plan + grocery list (2026-10-29) `[✓]`

- V3 migration: `recipes.course` (from source categories), planned slots may have unknown servings, `meal_plans.grocery_calculated_at`
- AI service: `app/planner.py` (D-035), `app/grocery.py` (D-036), `POST /internal/v1/meal-plans/generate`, `POST /internal/v1/grocery/compute`; importer stores course; retrieval helpers shared
- Backend: `plans/MealPlanService` + `MealPlanController` (D-037), shared `PantryService.usableStock` and `recipes/RecipeSafety` (suggestions refactored onto them), shared test `AiStub`
- New import summary `database/imports/2026-10-29-summary.json` (re-import with course labels)

| Check | Result |
|---|---|
| `python -m pytest -q` | 104 passed (new: planner 5, grocery 5, course parsing 1); test DB now one per module |
| `mvnw.cmd -B verify` | BUILD SUCCESS — 79 tests (new: MealPlanTests 6) |
| Mutations | expiring item keeps boosting after use → failed; backend re-check skipped → failed; recalculation drops checked state → failed (all restored) |
| Fresh DB, V1–V3, full re-import | 4,067 recipes; courses: main 1,141, breakfast 129, dessert 570, side 359, condiment 191, drink 66, none 1,611 |
| End-to-end (DB + AI service + backend + curl) | plan generated in ~6 s: complete 21/21, breakfast slots all breakfast recipes, lunch/dinner all mains, 21 distinct recipes; same `Idempotency-Key` → 200; check-off persisted and survived recalculation; vegetarian + no dairy/peanuts → partial 16/21 with "Not enough different breakfast recipes…" for 5 slots; 0 secrets in logs |
| Finding | 43 of 61 grocery items were **unresolved**: most source quantities are cups/tbsp/oz, which A-04 deliberately does not convert. Needs a product decision (see wrap-up) |

Commits `dc1d7dc`, `72ddef8`, `e43cc9a`, pushed. CI run [40778104944](https://github.com/13SunnySingh12/PantryChef/actions/runs/40778104944) on `e43cc9a`: **success** (all three jobs).

Process note: in the first end-to-end script, three checks failed only because Windows Python cannot open `/c/...` paths given inside `-c`; they were re-run with `C:/...` paths and passed.

### Loop 7 — Licensed recipe data, embeddings, retrieval, suggestions (2026-10-25/26) `[✓]`

- User approved (2026-10-25): Wikibooks Cookbook + TheMealDB + USDA FDC + all-MiniLM-L6-v2 + allergen dictionary (`DATA_SOURCE_OPTIONS.md`)
- V2 migration: `vector` extension, `recipe_chunks vector(384)`, `recipes.attribution` / `allergen_source`, read-only role `pantrychef_ai` (D-034)
- AI service: `scripts/fetch_sources.py`, `scripts/import_recipes.py`, `app/sources.py`, `app/ingredients.py`, `app/importer.py`, `app/embeddings.py`, `app/retrieval.py`, `POST /internal/v1/recipes/retrieve` behind `X-Service-Token` (D-030, D-032)
- Allergen dictionary `database/reference/ingredient_allergens.json` (D-031, **pending your review**)
- Backend: `recipes/AiClient`, `recipes/RecipeController` → `GET /api/v1/recipes/suggestions` with DB re-check of every AI result (D-033)
- CI: ai-service job runs DB integration tests (Docker on the runner), compiles scripts, audits torch's upstream version separately

| Check | Result |
|---|---|
| Fetch (2026-10-25) | 3,799 Wikibooks pages, 791 TheMealDB meals; manifest with SHA-256 |
| Dry-run parse (first) | Found and fixed: technique links taken as ingredients, "1⁄2" fraction slash, descriptors before units ("6 medium cloves"), U+2028 breaking line splitting, "curry leaves" → "curry", glued "200g" |
| Import into a throwaway compose DB | 4,067 recipes (3,276 Wikibooks after skipping 523 pages without a usable ingredient list + 791 TheMealDB), 33,962 ingredient rows, 4,067 embeddings, 41 MB; 722 allergen-complete |
| Re-import | `chunks_unchanged: 4067`, no new embeddings, counts identical |
| Real retrieval as `pantrychef_ai` | 100–400 ms; spinach expiring tomorrow ranks spinach dishes first; dairy exclusion removed 582 candidates, all results allergen-complete |
| `python -m pytest -q` | 93 passed (incl. 16 DB integration tests on a throwaway pgvector container) |
| `mvnw.cmd -B verify` | BUILD SUCCESS — 73 tests (new: RecipeSuggestionTests 6, SchemaConstraintsTests +2) |
| Mutations | backend re-check removed → failed; expiry weight 0 → **first survived** (alphabetical tie-break hid it), test rewritten, then failed; JDK h2c upgrade restored → failed |
| End-to-end (DB + FastAPI + backend jar + curl) | First run: **504 timeouts** — the JDK client's h2c upgrade header is never answered by uvicorn (my test stub ignored it). Fixed with HTTP/1.1 + a stub assertion. Second run: suggestions with permanent Wikibooks revision links and CC BY-SA credit; vegetarian + no dairy/peanuts excluded 638; direct AI call without token → 401; AI stopped → 503; 0 secrets in logs |
| `pip-audit` | **Found** `setuptools 78.1.0` and `filelock 3.32.3` advisories (stale copies from the PyTorch index) → upgraded to 84.2.1 / 4.2.4, re-locked; now no known vulnerabilities; torch base version audited separately (clean) |

Commits `f0698c9`, `9671ffe`, `419f8cb`, `12a5cc7`, `a79fc57`, `d940cb0`, pushed. CI run [40319658053](https://github.com/13SunnySingh12/PantryChef/actions/runs/40319658053) on `d940cb0`: **success** — frontend 11 s, backend 55 s, ai-service 1 min 49 s (CPU torch install + pgvector integration tests + both audits).

Not done here: nutrition import from USDA FDC (L9, needs a free data.gov key or the bulk download); the cuisine classifier (L10); the optional TheMealDB `strArea` normalization (L10).

### Loop 6 — Diet/allergen preferences + eligibility rule (2026-10-25) `[✓]`

- `database/fixtures/eligibility_cases.json`: 18 synthetic cases (expected reason codes)
- `preferences/Eligibility.java` and `ai-service/app/eligibility.py`: the same fail-closed rule (D-028)
- `preferences/PreferencesController`: `GET/PUT /api/v1/preferences` (D-029)

| Check | Result |
|---|---|
| `mvnw.cmd -B verify` | BUILD SUCCESS — 65 tests, 0 failures (new: EligibilityTests 19, PreferencesTests 3) |
| `python -m pytest -q` (ai-service) | 20 passed (18 fixture cases + unknown diet + health) |
| Mutation: drop "incomplete allergen data is unsafe" (Java / Python) | Java 4 failures, Python 3 failed → both restored |
| Preferences: dedupe + replace, unknown/uppercase/null codes, missing list, >20 entries | 400 with element-level field errors; stored set unchanged |
| Isolation + logging | Bob sees none of Alice's preferences; "shellfish" never appears in logs |
| Regression: frontend lint/test; ai-service compile | pass |

Commits `b29f641` (feat), `f913ecf` (docs), pushed. CI run [40217580879](https://github.com/13SunnySingh12/PantryChef/actions/runs/40217580879) on `f913ecf`: **success** (all three jobs).

Process slip, caught and fixed: during the mutation run the Java and Python backups were named `E.bak` and `e.bak`, which are the same file on Windows, so restoring briefly put the Python text into `Eligibility.java`. It was noticed from the change notice, rewritten from the original source, checked (`head`, mutant markers absent), and the full suite re-run green before commit. Backups now always use distinct names.

### Loop 5 — Daily expiry scan + in-app alerts (2026-10-25) `[✓]`

- `alerts/ExpiryAlerts`: set-based, idempotent reconciliation; `@Scheduled` daily 00:05 APP_TIMEZONE (`pantrychef.expiry.cron` overridable) + one scan at startup; per-item reconciliation called from every pantry change in the same transaction (D-027)
- `alerts/ExpiryAlertController`: `GET /api/v1/expiry-alerts` — open alerts with item name, remaining stock, expiry date, days until expiry; earliest expiry first; owner-scoped
- `@EnableScheduling`; `PantryService` now takes "today" from `ExpiryAlerts.today()` (one date source)

| Check | Result |
|---|---|
| `mvnw.cmd -B verify` | BUILD SUCCESS — 43 tests, 0 failures (new: ExpiryAlertTests 5) |
| Boundary scan at a fixed date (2031-03-10), run 3× | expired (−1 day), expiring_soon (today, +3 days); none for +4, no expiry, finished, deleted; one row per item |
| 6 parallel scans | still one alert row |
| Mutation: no resolve on type change | `alertMovesFromSoonToExpiredKeepingHistory` failed → restored |
| Mutation: no reconcile after stock events | `pantryChangesReconcileAlertsImmediately` failed → restored |
| Real run (compose DB + jar) | item created → alert immediately; item inserted directly in the DB while the app was stopped → restart log "Expiry scan for 2026-10-25: 0 alerts resolved, 1 created" → list shows expired Bread (−1) before Paneer (+1); no secrets in logs |
| Regression: frontend lint/test; ai-service pytest | pass |

Commits `2527562` (feat), `4ca3330` (docs), pushed. CI run [40216960993](https://github.com/13SunnySingh12/PantryChef/actions/runs/40216960993) on `4ca3330`: **success** (all three jobs).

Covered: QA-007, QA-008, QA-003 (alerts). Not built: optional resolve route API-012 (D-027).

### Loop 4 — Pantry items + lifecycle events (2026-10-25) `[✓]`

- `pantry/PantryController` + `PantryService`: `GET/POST /api/v1/pantry-items`, `GET/PATCH/DELETE /{itemId}`, `POST /{itemId}/events` (D-025, D-026)
- `pantry/Expiry`: the single A-01 rule (`expired` / `expiring_soon` today…today+3 / `ok` / `none`) — reused by the L5 scheduler; items return `expiryStatus` and `daysUntilExpiry`
- `pantry/Vocabulary`: category/unit/event codes matching the DB domains
- Config `pantrychef.timezone` (`APP_TIMEZONE`, default Asia/Kolkata) and `pantrychef.currency` (`APP_CURRENCY`, default INR)
- `ApiException.invalidField` for cross-field validation; registration's byte-length check now uses it

| Check | Result |
|---|---|
| `mvnw.cmd -B verify` | BUILD SUCCESS — 38 tests, 0 failures (new: PantryTests 11, ExpiryTests 2) |
| Mutation: row lock removed | `concurrentEventsNeverDriveStockNegative` failed (DB check blocked negatives as 500s) → restored |
| Mutation: owner filter removed | `anotherUsersItemIsInvisibleAndImmutable` failed → restored |
| Real run (compose DB + jar, curl) | create 201 (INR, expiring_soon) → used 201 → same `Idempotency-Key` 200 `replayed:true` → finished 201 → delete 204 → list `[]`; DB holds 2 events (used 1, finished 1) |
| Regression: frontend lint/test; ai-service pytest | pass |

Commits `a028f61` (feat), `d79b3bd` (docs), pushed. CI run [40178181695](https://github.com/13SunnySingh12/PantryChef/actions/runs/40178181695) on `d79b3bd`: **success** (all three jobs).

Covered: QA-003 (items/events), QA-004, QA-005, QA-006 (event distinction; savings figures in L9), QA-019, QA-020 (API part).

### Loop 3 — Error envelope + accounts (2026-10-25) `[✓]`

- `web/RequestIdFilter` (safe incoming `X-Request-Id` or new UUID; MDC + response header), `web/ApiErrors` (§7.1 envelope for validation, malformed body, 404/405/415, `ApiException`, sanitized 500), `web/ApiException`
- `security/SecurityConfig` (stateless bearer JWT, HS256, issuer check, envelope 401/403, BCrypt), `security/RateLimiter`, `security/CurrentUser`
- `auth/AuthController`: `POST /api/v1/auth/register` (201), `/login` (200 token, 60 min), `/logout` (204), `GET /api/v1/auth/me`
- Config: `pantrychef.jwt.*` from `JWT_SIGNING_SECRET` / `JWT_ISSUER`; `.env.example` updated; log lines carry the request ID
- Decisions D-020…D-024 (JdbcClient, auth details, rate limits, registration disclosure limitation, sanitized error logging)

| Check | Result |
|---|---|
| `mvnw.cmd -B verify` | BUILD SUCCESS — 25 tests, 0 failures (AuthTests 11, SchemaConstraintsTests 10, RateLimiterTests 2, ApiErrorsTests 1, context 1) |
| Mutation check: issuer validation removed | `AuthTests` failed as expected (wrong-issuer token accepted) → restored, suite green |
| Real run: compose DB + jar, curl register → login → me | 201 → token → 200 with own email |
| Real run: unauthenticated `GET /api/v1/pantry-items` | 401 envelope with request ID; `X-Content-Type-Options`, `X-Frame-Options: DENY`, no-store cache headers |
| Real run without `JWT_SIGNING_SECRET` | Startup refused ("Could not resolve placeholder 'JWT_SIGNING_SECRET'") |
| Real-run log grep for token, signing secret, DB password, user password | 0 matches; no generated default password |
| Regression: frontend lint/test; ai-service pytest | pass |

Commits `76cdb96` (feat), `2b739c8` (docs), pushed. CI run [40177427582](https://github.com/13SunnySingh12/PantryChef/actions/runs/40177427582) on `2b739c8`: **success** (all three jobs).

Covered tests: QA-001, QA-002, QA-021 (missing/garbage/forged/expired/wrong-issuer/unknown-subject tokens), QA-003 identity part. Not run: CORS (no browser client yet; L11 decides proxy vs CORS).

### Loop 2 — Schema v1 (2026-10-25) `[✓]`

- `database/migrations/V1__core_schema.sql`: 11 tables, 4 vocabulary domains, FKs, uniqueness, check constraints (D-017); `recipe_chunks` deferred until the embedding model is approved
- Backend: JDBC, Flyway (+ PostgreSQL module), PostgreSQL driver; Testcontainers (`pgvector/pgvector:pg17`) for tests; migrations mapped onto the classpath from `database/migrations/`
- `compose.yaml` (local DB on `127.0.0.1:5440`), `.env.example` (placeholders only), datasource read from `.env` or environment
- Recorded `APP_TIMEZONE=Asia/Kolkata`, `APP_CURRENCY=INR`

| Check | Result |
|---|---|
| `mvnw.cmd -B verify` | BUILD SUCCESS — 11 tests, 0 failures (`SchemaConstraintsTests` 10, context load 1) |
| First run of the constraint tests | **Failed as intended**: `NaN` price was accepted (Postgres orders NaN above all numbers) → added `<> 'NaN'` to all 12 numeric checks; one test had a wrong date (fixed) |
| Real startup: compose DB (throwaway project + random password in shell env) + `java -jar` | Flyway "Successfully applied 1 migration … now at version v1"; app started; 12 tables in `public` (11 + Flyway history); password not in log |
| Startup via env-file import (temp file in scratchpad) | Same success — `.env`-style file feeds the `${…}` placeholders |
| Regression: frontend lint/test/build; ai-service pytest | all pass |

Commits `f30e259` (schema), `9f54223` (docs), pushed to `main`. CI run [40176657244](https://github.com/13SunnySingh12/PantryChef/actions/runs/40176657244) on `9f54223`: **success** (frontend, backend incl. Testcontainers schema tests, ai-service).

Not run: migrations against hosted Supabase (not in use, D-005); DB role separation (L7).

### Loop 1 — Foundation (2026-10-25) `[✓]`

- `git init -b main`, remote `origin` = `https://github.com/13SunnySingh12/PantryChef.git`; root `.gitignore`, `README.md`
- `frontend/`: create-vite 9.3.1 React JS template, demo assets removed; Vitest + Testing Library + jsdom; oxlint `--deny-warnings`; one render test
- `backend/`: Spring Initializr (Boot 4.1.2, Java 21, webmvc + validation), Maven Wrapper (Maven 3.10.0); empty pom metadata removed; context-load test
- `ai-service/`: Python 3.12 venv (ignored), FastAPI 0.162.0, `GET /internal/v1/health`; pytest with warnings as errors; full `pip freeze` lock; `httpx2` test client
- `.github/workflows/ci.yml`: frontend / backend / ai-service jobs, SHA-pinned actions, `contents: read`, `npm audit` + `pip-audit`, no deploy stage
- `.env.example` deliberately **not** added yet: nothing reads env vars until L2/L3

| Check (local, Windows) | Result |
|---|---|
| `npm ci` / `npm run lint` / `npm test` / `npm run build` | exit 0 each; 1/1 tests passed |
| `npm audit --audit-level=high` | 0 vulnerabilities |
| `mvnw.cmd -B -q verify` | exit 0; Tests run: 1, Failures: 0, Errors: 0 |
| `python -m pytest -q` (warnings as errors) | 1 passed |
| `python -m compileall -q app` | exit 0 |
| `uvx pip-audit==2.10.1 -r requirements.txt` | No known vulnerabilities |
| Staged-diff secret + attribution scan; global commit hooks | No secrets; one false positive reworded; hooks passed |

Commits: `b80e147` docs, `a6ceef6` scaffold, `923107a` ci. Push: `main -> main` (new branch) succeeded.
CI: run [40175915022](https://github.com/13SunnySingh12/PantryChef/actions/runs/40175915022) — **success** (frontend, backend, ai-service all success).
Not run yet: deliberate-failure CI run (QA-024, planned for L12); pull-request trigger (no PR opened yet).

- [✓] Read `PantryChef.md` (L1–111) and `PantryChef_Complete.md` (L1–1400) in full
- [✓] Inspected workspace: not a git repo, only the two specs; no `.env`, manifests or code
- [✓] Inspected remote `13SunnySingh12/PantryChef`: public, empty, no default branch, ADMIN access
- [✓] Inspected toolchain: Node 24.16, Java 21.0.11, Maven 3.9.16, Python 3.12.10, Docker 29.8 with `pgvector/pgvector:pg17`, local PG 17 without pgvector
- [✓] Inspected Supabase (read-only): project "Sunny's Projects", empty `public` schema, `vector` 0.8.2 available, not installed
- [✓] Read `~/.agents/SKILL-ROUTING.md`, Ponytail 4.10.0 rules (active via hook), frontend skill headers (taste/emil/impeccable); ChaiUI not applicable
- [✓] Ran Graphify on the specs (254 nodes / 602 edges / 10 communities)
- [✓] Wrote audit, traceability, loop plan, decisions/blockers, this log
- [✓] L1 Foundation (see below)
- [✓] L2 Schema v1 (see below)
- [✓] L3 Error envelope + auth (see below)
- [✓] L4 Pantry CRUD + lifecycle events (see below)
- [✓] L5 Expiry scheduler + alerts (see below)
- [✓] L6 Preferences + diet/allergen eligibility (see below)
- [✓] L7 Recipe ingestion + retrieval + suggestions (see below)
- [✓] Allergen dictionary approved (D-031, 2026-10-29)
- [✓] L8 Meal plan + grocery list (see below)
- [✓] Grocery unit conversions approved and implemented (D-038)
- [✓] A-10 confirmed: today by default, optional start date up to 7 days ahead
- [✓] L9 Dashboard (see below)
- [!] Decide nutrition data scope (D-040)
- [✓] L10 Cuisine classifier + waste predictor (see below)
- [✗] Waste model training blocked: no real labelled history (B-03)
- [✓] L11 Frontend (see below)
- [ ] L12 CI hardening — next
- [✓] A-01…A-08 approved by the user (2026-10-25); B-04 push authorization granted
- [!] B-01 recipe dataset + license (blocks real RAG/plans/nutrition/cuisine)

## Commands executed in Phase 0 (all read-only except writing graphify-out/ and docs/)

| Command | Result |
|---|---|
| `git rev-parse --show-toplevel` | exit 128, "not a git repository" |
| `gh repo view 13SunnySingh12/PantryChef --json …` | isEmpty=true, PUBLIC, ADMIN |
| `git ls-remote https://github.com/13SunnySingh12/PantryChef.git` | no refs |
| `node/java/mvn/python/docker/psql --version` | versions recorded in REPOSITORY_AUDIT.md §2 |
| PyPI / start.spring.io / npm registry version lookups | recorded in REPOSITORY_AUDIT.md §2 |
| Supabase MCP `list_projects`, `list_tables`, `list_extensions` | recorded in REPOSITORY_AUDIT.md §3 |
| Graphify detect → extract → build → cluster → report → export html | success; minor collapsed-edge warning |

Tests executed: **none** (no code exists). Git commits/pushes: **none**.

## Change history

| Date | Change |
|---|---|
| 2026-10-25 | Phase 0 audit records created |
| 2026-10-25 | Loop 1 foundation scaffold + CI |
| 2026-10-25 | Loop 2 schema v1, local DB, `.env.example` |
| 2026-10-25 | Loop 3 error envelope, request IDs, accounts + JWT |
| 2026-10-25 | Loop 4 pantry CRUD, lifecycle events, expiry rule |
| 2026-10-25 | Loop 5 daily expiry scan, live alert reconciliation, alerts API |
| 2026-10-25 | Loop 6 preferences API, shared eligibility rule + fixture |
| 2026-10-25 | Data-source research and approval |
| 2026-10-26 | Loop 7 recipe import, embeddings, retrieval, suggestions |
| 2026-10-29 | Loop 8 meal plans, grocery list, check-off |
| 2026-10-29 | Approved oz/lb and US spoon/cup conversions |
| 2026-10-29 | Loop 9 dashboard API |
| 2026-10-29 | Loop 10 cuisine classifier, waste insufficient-data contract |
| 2026-10-29 | Loop 11 frontend |
| 2026-10-29 | Loop 12 CI dependency scan, PR trigger and deliberate-failure proof |
| 2026-10-29 | Loop 13 security review, clean setup, performance baseline, release checklist |
| 2026-10-29 | D-031 dictionary approved, A-10 start date, D-048 cuisine pass mark |

## Next safe action

No loops remain. Owner actions: re-import recipes so stored data uses dictionary 2026-10-29.1; replace the provisional cuisine pass mark (D-048) when a product requirement exists; B-03 stays blocked until real usage history exists.
