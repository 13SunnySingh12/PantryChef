# PantryChef — Loop Plan

Each loop = one coherent slice: plan → smallest change → focused tests → regression → review/clean → traceability → manual-action check → commit (push when authorized). ROAD IDs from PantryChef_Complete.md §10.

| Loop | Scope | ROAD | FR / NFR | Needs before "done" | Verification |
|---|---|---|---|---|---|
| L1 | Foundation: `git init` (`main`), `.gitignore`, `.env.example` (placeholders), README skeleton; scaffold `frontend/` (Vite React JS, ESLint, Vitest), `backend/` (Spring Boot 4.1, Java 21, Maven Wrapper), `ai-service/` (FastAPI, pytest), each with one smoke test | 001–004 | NFR-004, NFR-006 | B-04 to push | `npm ci && npm run lint && npm test && npm run build`; `mvnw.cmd -B verify`; `python -m pytest -q` |
| L2 ✓ | Schema v1 (Flyway, `database/migrations/`): 11 tables + constraints; `recipe_chunks` deferred to L7 (D-017); local compose DB + `.env.example` | 005–006 | NFR-010, FR-024 | — | Testcontainers pgvector: fresh apply, rerun no-op, constraint tests; real startup |
| L3 ✓ | Error envelope + request-ID filter (D-019); auth: register/login/me/logout, BCrypt, JWT HS256 validation, rate limit, owner-scoping helper | 009, 023 | FR-001, FR-002, NFR-001 | A-07 | QA-001/002/003/021 |
| L4 ✓ | Pantry CRUD + lifecycle events (atomic, idempotency key, optimistic locking, soft delete) | 010, 012 | FR-003–005, FR-024 | A-04, A-05 | QA-004/005/006/019 |
| L5 ✓ | Daily expiry scheduler + alerts API (fixed `Clock`, upsert reconciliation) | 011 | FR-006–008, FR-023 | A-01, A-02 | QA-007/008 |
| L6 ✓ | Preferences API + shared diet/allergen eligibility predicate (Java + Python, same fixtures) | 015, 025 | FR-011, FR-012 | A-08 | QA-010 |
| L7 ✓ | AI service: ingestion/normalize/chunk/embed (idempotent, manifest), retrieval + ranking + citations; internal auth; Spring `/recipes/suggestions` orchestration with timeouts | 008, 017, 018 | FR-009, FR-010, FR-016 | **B-01**, B-02 | QA-009/022/025/026, QA-017 |
| L8 ✓ | Meal plan (21 slots, unavailable reasons) + grocery list (unit conversion, unresolved) + check-off | 021 | FR-015–018 | A-10, B-01 | QA-011/012 |
| L9 ✓ | Dashboard: waste/money saved, nutrition completeness, chart data | 012, 022 | FR-019–022 | A-06, nutrition source | QA-013/014 |
| L10 | Cuisine classifier (train/eval script, versioned artifact) + waste predictor (`insufficient_data` until real history) | 019, 020 | FR-013, FR-014 | labelled data, A-11 | QA-015/016 |
| L11 | Frontend: auth, pantry, alerts, preferences, recipes, plan, grocery, dashboard (taste → emil → impeccable) | 013–016 | NFR-007/008, all UI | APIs stable | Vitest/RTL, keyboard + viewport checks, Impeccable audit |
| L12 | CI workflow: 3 jobs (+ dependency scans), no deploy; verify pass and deliberate fail | 030–032 | FR-025/026, NFR-012 | B-04 | Real GitHub Actions runs |
| L13 | Security review, E2E journey, perf baseline, ponytail-audit, attribution/secret scans, docs final | 024, 026–029, 033–034 | NFR-002/005/009/011 | — | QA-017–024, ROAD-033 clean setup |

Ordering: L1→L2→L3→L4→L5 are unblocked except for product defaults A-01…A-07. L6 and the code/fixtures half of L7–L8 can proceed on **synthetic test fixtures** before B-01; real suggestions, plans and nutrition stay Blocked until B-01. CI (L12) can be added right after L1 if pushes are authorized, then extended each loop.

Major-change regression gate: after L2, L3, L7, L11, L12 run all three module suites + app startup.
