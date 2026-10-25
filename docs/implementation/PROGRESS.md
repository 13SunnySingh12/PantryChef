# PantryChef — Progress Log

Legend: `[✓]` completed and verified · `[→]` in progress · `[ ]` pending · `[!]` manual action required · `[✗]` failed/blocked

## Current state (2026-10-25)

**Loop 2 (schema v1) complete and verified locally; CI result recorded below. Next: L3 error envelope + auth.**

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
- [ ] L3 Error envelope + auth — next
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

## Next safe action

L3: error envelope (§7.1, 400 policy D-011) + request-ID filter; Spring Security with stateless bearer JWT (D-008, A-07: 60-min access token, BCrypt, generic login errors, login rate limit); `register`/`login`/`logout`/`me`; cross-user test harness. Needs a local `JWT_SIGNING_SECRET` only to *run* the app (tests generate their own).
