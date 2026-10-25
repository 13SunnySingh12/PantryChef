# PantryChef — Progress Log

Legend: `[✓]` completed and verified · `[→]` in progress · `[ ]` pending · `[!]` manual action required · `[✗]` failed/blocked

## Current state (2026-10-25)

**Loop 6 (preferences + eligibility) complete and verified locally; CI result recorded below. Next: L7 recipe retrieval — real data blocked by B-01 (dataset + license), code can proceed on synthetic fixtures.**

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
- [ ] L7 Recipe ingestion + retrieval — next (real data `[!]` B-01)
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

## Next safe action

L7 needs two user decisions before real recipe suggestions can exist: **B-01** (which recipe dataset, after its license is checked) and **B-02** (embedding model download). Without them, the safe independent work is: internal FastAPI service auth, the retrieval/ranking/citation code and its Spring orchestration, all tested only on clearly-labelled synthetic fixture recipes; no real dataset is ingested and no model is downloaded.
