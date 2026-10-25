# PantryChef — Decisions, Conflicts and Blockers

Status legend: **Decided** (engineering choice inside the spec's latitude, reversible) · **Needs approval** (product/legal decision; the proposed default is *not* treated as confirmed) · **Blocked** (external dependency only the user can supply).

## 1. Engineering decisions (reversible, within spec latitude)

| ID | Decision | Basis | Rollback |
|---|---|---|---|
| D-001 | Monorepo layout `frontend/`, `backend/`, `ai-service/`, `database/`, `docs/`, `.github/workflows/` (lowercase). Specs stay at repo root, unrenamed. | Complete §5.4; repo is empty so no convention to preserve; case-sensitive CI favours one lowercase scheme | Rename folders + update CI paths |
| D-002 | Frontend: Vite 8 + React 19 (JavaScript), Vitest + Testing Library (jsdom), **oxlint** (the current `create-vite` React template's linter; kept instead of adding ESLint), `--deny-warnings`. React Router added with the first second page; chart library chosen in the dashboard loop. | §5.1 "Vite is a proposed option"; TBD-021/028 | Swap build tool / linter |
| D-015 | Python lock = full `pip freeze` in `ai-service/requirements.txt` (direct + transitive pins). Test client uses `httpx2` (Starlette deprecates `httpx` for TestClient). pytest treats warnings as errors. | §5.6 pin strategy | Switch to a compile/lock tool if deps grow |
| D-016 | CI: one workflow, three independent jobs; `contents: read`; first-party actions pinned to commit SHAs; `npm audit --audit-level=high` and `pip-audit` gate. Java dependency scanner deferred (TBD-022: OWASP dependency-check needs an NVD API key for usable speed). | §11.6, §8.8 | — |
| D-003 | Backend: Spring Boot 4.1.x, Java 21, Maven Wrapper, Spring Security, Spring Data JPA, Flyway migrations, Testcontainers (pgvector image) for DB tests. | §5.8, §11.1; installed toolchain | — |
| D-004 | AI service: Python 3.12, FastAPI, pydantic, psycopg 3, scikit-learn, sentence-transformers; pytest. venv at `ai-service/.venv` (ignored). | §5.1; S-6 | — |
| D-005 | **Local dev and CI database = Docker `pgvector/pgvector:pg17`.** Hosted Supabase is optional and only used if the user approves a specific project (see A-09). | §6.1 allows a tested local PG+pgvector alternative; §11.2 prefers local for CI; hosted project "Sunny's Projects" is not identified as PantryChef's | Point JDBC/DSN at Supabase with TLS |
| D-006 | One migration tool (Flyway, owned by `backend/`, SQL files in `database/migrations/`). FastAPI never migrates schema. | §6.4 ordered migrations; avoid two schema owners | — |
| D-007 | Python 3.12, not 3.13. | Installed; all chosen packages support ≥3.10/3.11; §5.8 "avoid a new runtime until compatibility confirmed" | Bump CI + local pin |
| D-008 | Auth transport: **bearer access token held in browser memory**, sent as `Authorization: Bearer`; no cookies → no ambient-credential CSRF surface; refresh tokens out of MVP (re-login on expiry). HS256 signing key from env. | §8.2 asks for one explicit model; simplest that meets FR-001 | Add HttpOnly refresh cookie + CSRF later |
| D-009 | Spring Boot → FastAPI auth: shared secret header (`SERVICE_TO_SERVICE_SECRET`), constant-time compare, timeouts on every call. FastAPI receives only normalized ingredients/features, not user IDs as authority. | §3.5, §5.2, §8.3 | mTLS if ever needed |
| D-010 | FastAPI DB role: read-only on recipe tables only; no access to `users`/pantry tables. Spring Boot role owns user data. | §5.2, §6.3, RISK-009 | — |
| D-011 | Validation-error status: **400** with the §7.1 envelope for all DTO/schema failures (FastAPI's default 422 remapped to 400 for consistency). | §7.4 requires one documented policy | — |
| D-012 | Planning records live in `docs/implementation/` (lowercase, per D-001) instead of the prompt's `Docs/Implementation/`. | Consistency with spec layout | — |
| D-013 | Graphify output (`graphify-out/`) will be git-ignored (local analysis cache). | Not project source | — |
| D-014 | **No LLM (Gemini/Groq) in the MVP.** No provider SDK, key, or route added. Revisit only if the user names a concrete feature. | Spec §1.6, §5.7, §8.7, TBD-014; prompt §1 conflict rule | Add isolated optional adapter |

## 2. Product decisions

**2026-10-25: the user approved the proposed defaults A-01 to A-08**, so they are now Decided. Two values are still open: the `APP_TIMEZONE` value (A-01) and the `APP_CURRENCY` value (A-03). The user said these would be supplied, but none were provided yet. They are needed before L2 (currency column default) and L5 (scheduler). A-09 to A-12 remain proposals.

Push authorization (B-04) granted on 2026-10-25: verified milestones may be committed and pushed to `main` on `13SunnySingh12/PantryChef`.

| ID | Question (spec ref) | Proposed default | Needed by |
|---|---|---|---|
| A-01 | Expiry window & timezone (TBD-009, §2.3) | Calendar dates in one configured app timezone (`APP_TIMEZONE`; value **user to choose**, e.g. `Asia/Kolkata`); `expiring_soon` if `today ≤ expiry_date ≤ today+3` inclusive; `expired` if `expiry_date < today`; null expiry → no alert | Loop: scheduler (ROAD-011) |
| A-02 | "Flagged automatically" (TBD-010) | Computed expiry state + `expired` alert; item status unchanged (not discarded) | ROAD-011 |
| A-03 | Price basis & currency (§6.2, §7.2) | Store **total purchase price** for the initial quantity + one app currency (`APP_CURRENCY`, user to choose, e.g. INR); value per unit = price ÷ initial quantity | ROAD-006/012 |
| A-04 | Category & unit vocabulary (TBD-006, FR-004) | Categories: dairy, vegetables, fruits, grains, meat, seafood, bakery, beverages, condiments, frozen, snacks, other (explicit catch-all). Units: g, kg, ml, l, piece, pack, bunch, dozen. Conversions only g↔kg, ml↔l, piece↔dozen | ROAD-006/010, FR-017 |
| A-05 | Lifecycle semantics (§2.3, TBD-029) | `used` qty>0 reduces stock; `discarded` qty>0 reduces stock; `finished` = use all remaining (event quantity = remaining); stock never < 0; correction = edit item fields (no event reversal in MVP); delete = soft delete, history kept, never counted as saved | ROAD-010/012 |
| A-06 | Waste/money saved formula (TBD-011, S-7) | Waste saved = sum of `used`/`finished` event quantities (and item count) where `occurred_at` date ≤ expiry_date (or item had no expiry → excluded); money saved = that quantity × per-unit value; unknown price → reported as "unknown" count, not zero | ROAD-012/022 |
| A-07 | Auth lifecycle (TBD-007/008) | Access token 60 min, no refresh, logout = client clears token; no email verification / reset / MFA in MVP; password ≥ 8 chars; login rate limit | ROAD-009 |
| A-08 | Diet & allergen taxonomy and unknown policy (TBD-019/020) | Diets: vegetarian, vegan, gluten_free. Allergens: peanuts, tree_nuts, dairy, eggs, gluten, soy, fish, shellfish, sesame. A recipe is eligible for a declared exclusion only if `allergen_metadata_complete = true` **and** no ingredient carries that flag; diet label must be source-backed or derived from complete ingredient flags, else ineligible | ROAD-015/018/025 |
| A-09 | Hosted Supabase use (TBD-005) | Not used for MVP; local Docker only. If wanted, user names the project | Optional |
| A-10 | Meal plan rules (§3.4, TBD-025) | Start date = the day of generation (alternative: next Monday — **user to choose**); servings = recipe default; deterministic ranking with stable tie-break; regeneration creates a new plan, never overwrites; grocery list is a snapshot with explicit recalculation | ROAD-021 |
| A-11 | Waste predictor label (TBD-017) | Label = item had a `discarded` event on/before expiry+1 day; without ≥ N real labelled items return `insufficient_data` (no rule baseline dressed as ML) | ROAD-020 |
| A-12 | Retention / account deletion (TBD-024) | Out of MVP; keep history; document as limitation | Docs |

## 3. Blockers (user-supplied, cannot be fabricated)

| ID | Blocker | Blocks | What only the user can do |
|---|---|---|---|
| B-01 | **Licensed recipe dataset** with ingredients (ideally quantities/units), source attribution, and if possible cuisine labels, diet/allergen metadata and nutrition (TBD-001/012/016/018) | FR-009, FR-010, FR-011/012 on real data, FR-013, FR-015–018, FR-021, ROAD-008/017–021 | Choose a dataset and accept its license terms. Code + synthetic fixtures can be built before this; real suggestions cannot. Candidates to evaluate (terms **not yet verified**): RecipeNLG, Food.com (Kaggle), Kaggle "What's Cooking" (cuisine labels), TheMealDB API, a self-authored CC0 set; USDA FoodData Central for nutrition derivation |
| B-02 | Embedding model approval (TBD-013) | ROAD-017 | Approve model download (candidate `sentence-transformers/all-MiniLM-L6-v2`, 384-d, license per model card — to verify) |
| B-03 | Real usage history for waste prediction (TBD-017) | FR-014 trained model | None exists; endpoint will return `insufficient_data` until real history accrues |
| B-04 | Push authorization & branch (`main` proposed for the empty remote) | ROAD-004/030/032 | Confirm pushes to `13SunnySingh12/PantryChef` `main` |
| B-05 | Local secrets in an untracked `.env` (`JWT_SIGNING_SECRET`, `SERVICE_TO_SERVICE_SECRET`, DB password) | Running the app locally (not tests — tests generate throwaway values) | Generate and place locally when the run step is reached |

## 4. Conflict log

| Conflict | Sources | Safest choice | User decision needed |
|---|---|---|---|
| LLM providers | Prompt §1 vs Complete §1.6/§8.7/TBD-014 | D-014: none in MVP | Only if a concrete LLM feature is wanted |
| Folder layout | Prompt §1 vs Complete §5.4 | D-001 lowercase | No |
| Python version | Complete §5.8 (3.13) vs installed 3.12 | D-007 | No |
| QA → "BR-011" | Complete L942 | Map to NFR-005/NFR-003 | No |
