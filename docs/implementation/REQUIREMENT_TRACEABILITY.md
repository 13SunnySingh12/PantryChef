# PantryChef — Requirement Traceability

Status values: **Not implemented** · **Implemented (code evidence)** · **Executed and passed** · **Executed and failed** · **Blocked** · **Out of Scope**. Every requirement below is *Confirmed by source* (PantryChef_Complete.md §2.3/§2.5; short-spec section in brackets). As of 2026-10-25 nothing is implemented; code/test columns are filled as loops land.

## Functional requirements

| ID | Pri | Requirement [PantryChef.md] | Loop | Planned tests | Depends on | Code | Status |
|---|---|---|---|---|---|---|---|
| FR-001 | P0 | Register/login with JWT [§8] | L3 | QA-001, QA-002, QA-021 | — | `backend/…/auth/AuthController.java`, `security/SecurityConfig.java` | Executed and passed (`AuthTests`); limitation D-023 |
| FR-002 | P0 | Per-user data isolation [§8] | L3–L9 | QA-003, QA-021 | — | `security/CurrentUser.java`; owner-scoped SQL in `pantry/PantryService.java` | Auth, pantry items/events, alerts, preferences, plans, grocery items, dashboard passed (`AuthTests`, `PantryTests`, `ExpiryAlertTests`, `PreferencesTests`, `MealPlanTests`, `DashboardTests`) |
| FR-003 | P0 | Pantry CRUD [§1] | L4 | QA-004, QA-019 | — | `pantry/PantryController.java`, `PantryService.java` | Executed and passed (`PantryTests`) |
| FR-004 | P0 | Categories incl. explicit `other` [§1] | L4 | QA-004 | — | `pantry/Vocabulary.java`, `category_code` domain | Executed and passed |
| FR-005 | P0 | used/finished/discarded events [§1] | L4 | QA-005, QA-006 | — | `PantryService.recordEvent` | Executed and passed (atomic, idempotent, concurrent tests); savings side of QA-006 pending L9 |
| FR-006 | P0 | Daily idempotent expiry scan [§2] | L5 | QA-007 | — | `alerts/ExpiryAlerts.java` (`@Scheduled` + startup) | Executed and passed (`ExpiryAlertTests`: reruns, parallel scans); startup scan verified in a real run |
| FR-007 | P0 | Flag expired items [§2] | L5 | QA-008 | — | `pantry/Expiry.java`, `expiryStatus` on items, `expired` alerts | Executed and passed (`ExpiryTests`, `ExpiryAlertTests`) |
| FR-008 | P0 | In-app alerts by urgency, reconciled [§2] | L5 | QA-007 | — | `alerts/ExpiryAlertController.java` | Executed and passed (sorted, owner-scoped, reconciled on every change) |
| FR-009 | P0 | Pantry-based RAG suggestions [§3] | L7 | QA-009 | — | `ai-service/app/retrieval.py`, `backend/…/recipes/RecipeController.java`, `AiClient.java` | Executed and passed (`test_retrieval_api.py`, `RecipeSuggestionTests`) + real end-to-end run on 4,067 imported recipes |
| FR-010 | P0 | Citations to real stored recipes; versioned idempotent indexing [§3] | L7 | QA-009, QA-025 | — | `ai-service/app/importer.py`, `sources.py`, V2 migration, `database/imports/` | Executed and passed: every result is a stored recipe with URL/license/attribution; re-import embeds nothing (tests + real 4,067-recipe re-run) |
| FR-011 | P0 | Diet filters everywhere [§5] | L6–L8 | QA-010, QA-011 | — | `preferences/Eligibility.java`, `ai-service/app/eligibility.py`, `PreferencesController`, retrieval + backend re-check | Suggestions: Executed and passed (AI filter + backend re-check, mutation-tested); meal plans: Executed and passed (planner filter + backend re-check turns unsafe picks into explained gaps, mutation-tested) |
| FR-012 | P0 | Allergen exclusions, unknown ≠ safe [§5] | L6–L8 | QA-010, QA-011 | D-031 review | same as FR-011 + `database/reference/ingredient_allergens.json` | Suggestions: Executed and passed (unknown allergen data excluded; 722/4,067 recipes allergen-complete); dictionary pending owner review; meal plans: Executed and passed (planner filter + backend re-check turns unsafe picks into explained gaps, mutation-tested) |
| FR-013 | P1 | Cuisine classifier TF-IDF + LR [§4] | L10 | QA-015 | TBD-027 threshold | `ai-service/app/cuisine.py`, `scripts/train_cuisine.py`, `EstimatesController` | Trained on real labels and evaluated: test macro-F1 0.5156 vs baseline 0.0157 (D-041); code paths Executed and passed (`test_cuisine.py`, `EstimatesTests`), integrity check mutation-tested; real end-to-end run |
| FR-014 | P1 | Waste-risk predictor [§4] | L10 | QA-016 | A-11, **B-03 (no real history)** | `/internal/v1/waste/predict`, `GET /api/v1/pantry-items/{id}/waste-risk` | Insufficient-data contract Executed and passed; trained model **Blocked** by missing labelled history (D-042) |
| FR-015 | P0 | 7-day × 3-meal plan, explicit unavailable slots [§6] | L8 | QA-011 | A-10 start date (proposed default in use) | `ai-service/app/planner.py`, `backend/…/plans/MealPlanService.java` | Executed and passed (`test_planner_api.py`, `MealPlanTests`) + real end-to-end run |
| FR-016 | P0 | Near-expiry prioritization score [§3, §6] | L7–L8 | QA-009, QA-026 | — | `retrieval.py` (D-032 formula) | Suggestions: Executed and passed (equal-coverage test where only the expiry boost can decide; mutation with boost weight 0 fails it; deterministic repeat test); meal plans: Executed and passed (expiring items used first and stop boosting once used, mutation-tested) |
| FR-017 | P0 | Grocery list = plan − pantry, compatible units only [§6] | L8 | QA-012 | — | `ai-service/app/grocery.py` (D-036) | Executed and passed (`test_grocery.py`: aggregation, lower/equal/higher stock, incompatible/unknown units, no double counting) |
| FR-018 | P0 | Persisted, owner-scoped check-off [§6] | L8 | QA-012, QA-003 | — | `PATCH /api/v1/grocery-items/{id}`, recalculation keeps checks | Executed and passed (`MealPlanTests`, mutation-tested) |
| FR-019 | P1 | Waste-saved metric [§7] | L9 | QA-006 | — | `dashboard/DashboardService.java` (D-039) | Executed and passed (`DashboardTests`, mutation-tested; real HTTP run) |
| FR-020 | P1 | Money-saved metric [§7] | L9 | QA-013 | — | same | Executed and passed (pro-rata price, unknown price disclosed, per currency) |
| FR-021 | P1 | Weekly nutrition summary [§7] | L9 | QA-014 | **D-040 open** | same | Aggregation Executed and passed (one serving per meal, missing data counted, null not 0, mutation-tested); **no recipe has nutrition data yet**, so real values are all unknown |
| FR-022 | P1 | Bar/line charts [§7] | L9 (data), L11 (UI) | QA-014 | — | `frontend/src/pages/DashboardPage.jsx` (Recharts line + bar, text summary, data table) | Executed and passed (`app.test.jsx` dashboard test; real browser run); nutrition chart shows only when data exists (D-040) |
| FR-023 | P0 | In-app alerts only [§2, Out of scope] | L5 | review: no email/push/SMS deps | — | `GET /api/v1/expiry-alerts` only | Implemented; dependency review: no mail/push/SMS library in `pom.xml` |
| FR-024 | P0 | Input validation, safe stable errors | L2–L9 | QA-004, QA-019, QA-020 | — | `web/ApiErrors.java`, `web/RequestIdFilter.java`, DTO + DB checks | Auth + pantry passed (incl. NaN/Infinity/invalid date → 400, SQL/markup stored as data); UI rendering half of QA-020 pending L11 |
| FR-025 | P0 | Paid-free, fixture-based tests | L1–L11 | QA-018, QA-023 | — | — | Not implemented |
| FR-026 | P0 | CI on push + PR, no deploy [§9] | L1, L12 | QA-024 | — | `.github/workflows/ci.yml` | Implemented; push run 40175915022 passed; PR trigger + deliberate failure Not Run |

## Non-functional requirements

| ID | Requirement | Verified by | Status |
|---|---|---|---|
| NFR-001 | Secure authn/authz, hashes only | QA-001, QA-003, QA-021 | Authn: Executed and passed; authz per resource pending |
| NFR-002 | Privacy / sanitized logs | Log review + tests asserting no token/password in logs | Auth paths passed (`logsNeverContainPasswordsOrTokens` + real-run log grep) |
| NFR-003 | Free-core, no paid fallback | QA-018; dependency review | Implemented so far: local embeddings, free sources, no LLM/provider SDK in any manifest; QA-018 n/a while no LLM exists (D-014) |
| NFR-004 | Local reproducibility | ROAD-033 clean-setup run | Not implemented |
| NFR-005 | Predictable failures, safe errors | QA-017 | Executed and passed: envelope (`ApiErrorsTests`), AI down/slow/500 → 503/504/502 (`RecipeSuggestionTests` + real run with the AI service stopped), DB outage and missing model → 503 in the AI service |
| NFR-006 | Maintainability, pinned deps, contracts | Lockfiles, OpenAPI | Not implemented |
| NFR-007 | Accessibility | Frontend interaction tests, keyboard pass, Impeccable audit | Executed and passed: labelled fields with linked errors, keyboard focus ring checked in the browser, status as text + icon, contrast ≥ 5.09:1 for every token pair in both themes, charts with text summaries/tables; Impeccable detector clean (static + live login page) |
| NFR-008 | Responsive usability | Viewport checks (mobile/tablet/desktop) | Executed and passed in the built-in browser at 375×812, 768×1024 and 1280×800 (no horizontal page scroll, 44 px touch targets on touch screens), light and dark |
| NFR-009 | Measured performance baseline | ROAD-029 | Not implemented |
| NFR-010 | Data integrity constraints | QA-005, QA-025, DB integration tests | Executed and passed: DB constraints (`SchemaConstraintsTests`) + atomic/locked/idempotent stock events (`PantryTests`, incl. 10-way and 8-way concurrency) |
| NFR-011 | Honest test status | This file + PROGRESS.md | In effect (process) |
| NFR-012 | CI without deployment | QA-024; workflow review | Not implemented |

## QA → requirement map (Complete §9.3, with S-4 correction)

QA-001–002→FR-001 · QA-003→FR-002/FR-018 · QA-004→FR-003/FR-004/FR-024 · QA-005→FR-005 · QA-006→FR-005/FR-019 · QA-007→FR-006/FR-008 · QA-008→FR-007 · QA-009→FR-009/FR-010/FR-016 · QA-010→FR-011/FR-012 · QA-011→FR-015 · QA-012→FR-017/FR-018 · QA-013→FR-020 · QA-014→FR-021/FR-022 · QA-015→FR-013 · QA-016→FR-014 · QA-017→NFR-005 · QA-018→NFR-003 (spec wrote "BR-011", undefined) · QA-019–023→§8 security controls · QA-024→FR-026/NFR-012 · QA-025→FR-010/NFR-010 · QA-026→FR-016.

## Out of scope (Confirmed)

Cloud deployment/hosting/release/publishing/infrastructure · native mobile app · barcode scanning · email/push/SMS notifications · mandatory paid services · admin UI, shared households, public landing/onboarding, recipe authoring.
