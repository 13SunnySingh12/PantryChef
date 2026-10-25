# PantryChef — Requirement Traceability

Status values: **Not implemented** · **Implemented (code evidence)** · **Executed and passed** · **Executed and failed** · **Blocked** · **Out of Scope**. Every requirement below is *Confirmed by source* (PantryChef_Complete.md §2.3/§2.5; short-spec section in brackets). As of 2026-10-25 nothing is implemented; code/test columns are filled as loops land.

## Functional requirements

| ID | Pri | Requirement [PantryChef.md] | Loop | Planned tests | Depends on | Code | Status |
|---|---|---|---|---|---|---|---|
| FR-001 | P0 | Register/login with JWT [§8] | L3 | QA-001, QA-002, QA-021 | — | `backend/…/auth/AuthController.java`, `security/SecurityConfig.java` | Executed and passed (`AuthTests`); limitation D-023 |
| FR-002 | P0 | Per-user data isolation [§8] | L3–L9 | QA-003, QA-021 | — | `security/CurrentUser.java`; owner-scoped SQL in `pantry/PantryService.java` | Auth, pantry items/events, alerts, preferences passed (`AuthTests`, `PantryTests`, `ExpiryAlertTests`, `PreferencesTests`); plans/dashboard pending L8–L9 |
| FR-003 | P0 | Pantry CRUD [§1] | L4 | QA-004, QA-019 | — | `pantry/PantryController.java`, `PantryService.java` | Executed and passed (`PantryTests`) |
| FR-004 | P0 | Categories incl. explicit `other` [§1] | L4 | QA-004 | — | `pantry/Vocabulary.java`, `category_code` domain | Executed and passed |
| FR-005 | P0 | used/finished/discarded events [§1] | L4 | QA-005, QA-006 | — | `PantryService.recordEvent` | Executed and passed (atomic, idempotent, concurrent tests); savings side of QA-006 pending L9 |
| FR-006 | P0 | Daily idempotent expiry scan [§2] | L5 | QA-007 | — | `alerts/ExpiryAlerts.java` (`@Scheduled` + startup) | Executed and passed (`ExpiryAlertTests`: reruns, parallel scans); startup scan verified in a real run |
| FR-007 | P0 | Flag expired items [§2] | L5 | QA-008 | — | `pantry/Expiry.java`, `expiryStatus` on items, `expired` alerts | Executed and passed (`ExpiryTests`, `ExpiryAlertTests`) |
| FR-008 | P0 | In-app alerts by urgency, reconciled [§2] | L5 | QA-007 | — | `alerts/ExpiryAlertController.java` | Executed and passed (sorted, owner-scoped, reconciled on every change) |
| FR-009 | P0 | Pantry-based RAG suggestions [§3] | L7 | QA-009 | B-01, B-02 | — | Not implemented (real data Blocked by B-01) |
| FR-010 | P0 | Citations to real stored recipes; versioned idempotent indexing [§3] | L7 | QA-009, QA-025 | B-01, B-02 | — | Not implemented (Blocked by B-01) |
| FR-011 | P0 | Diet filters everywhere [§5] | L6–L8 | QA-010, QA-011 | — | `preferences/Eligibility.java`, `ai-service/app/eligibility.py`, `PreferencesController` | Rule + stored preferences: Executed and passed (fixture parity in both languages); applying it to suggestions/plans pending L7–L8 |
| FR-012 | P0 | Allergen exclusions, unknown ≠ safe [§5] | L6–L8 | QA-010, QA-011 | — | same as FR-011 | Same status as FR-011; fail-closed rule mutation-tested in both languages |
| FR-013 | P1 | Cuisine classifier TF-IDF + LR [§4] | L10 | QA-015 | B-01 (labels) | — | Not implemented (Blocked by labelled data) |
| FR-014 | P1 | Waste-risk predictor [§4] | L10 | QA-016 | A-11, B-03 | — | Not implemented (trained model Blocked by B-03) |
| FR-015 | P0 | 7-day × 3-meal plan, explicit unavailable slots [§6] | L8 | QA-011 | A-10, B-01 | — | Not implemented |
| FR-016 | P0 | Near-expiry prioritization score [§3, §6] | L7–L8 | QA-009, QA-026 | — | — | Not implemented |
| FR-017 | P0 | Grocery list = plan − pantry, compatible units only [§6] | L8 | QA-012 | A-04 | — | Not implemented |
| FR-018 | P0 | Persisted, owner-scoped check-off [§6] | L8 | QA-012, QA-003 | — | — | Not implemented |
| FR-019 | P1 | Waste-saved metric [§7] | L9 | QA-006 | A-06 | — | Not implemented |
| FR-020 | P1 | Money-saved metric [§7] | L9 | QA-013 | A-03, A-06 | — | Not implemented |
| FR-021 | P1 | Weekly nutrition summary [§7] | L9 | QA-014 | B-01 (nutrition source) | — | Not implemented |
| FR-022 | P1 | Bar/line charts with summaries [§7] | L9 | QA-014 | — | — | Not implemented |
| FR-023 | P0 | In-app alerts only [§2, Out of scope] | L5 | review: no email/push/SMS deps | — | `GET /api/v1/expiry-alerts` only | Implemented; dependency review: no mail/push/SMS library in `pom.xml` |
| FR-024 | P0 | Input validation, safe stable errors | L2–L9 | QA-004, QA-019, QA-020 | — | `web/ApiErrors.java`, `web/RequestIdFilter.java`, DTO + DB checks | Auth + pantry passed (incl. NaN/Infinity/invalid date → 400, SQL/markup stored as data); UI rendering half of QA-020 pending L11 |
| FR-025 | P0 | Paid-free, fixture-based tests | L1–L11 | QA-018, QA-023 | — | — | Not implemented |
| FR-026 | P0 | CI on push + PR, no deploy [§9] | L1, L12 | QA-024 | — | `.github/workflows/ci.yml` | Implemented; push run 40175915022 passed; PR trigger + deliberate failure Not Run |

## Non-functional requirements

| ID | Requirement | Verified by | Status |
|---|---|---|---|
| NFR-001 | Secure authn/authz, hashes only | QA-001, QA-003, QA-021 | Authn: Executed and passed; authz per resource pending |
| NFR-002 | Privacy / sanitized logs | Log review + tests asserting no token/password in logs | Auth paths passed (`logsNeverContainPasswordsOrTokens` + real-run log grep) |
| NFR-003 | Free-core, no paid fallback | QA-018; dependency review | Not implemented |
| NFR-004 | Local reproducibility | ROAD-033 clean-setup run | Not implemented |
| NFR-005 | Predictable failures, safe errors | QA-017 | Envelope passed (`ApiErrorsTests`); downstream timeouts pending L7 |
| NFR-006 | Maintainability, pinned deps, contracts | Lockfiles, OpenAPI | Not implemented |
| NFR-007 | Accessibility | Frontend interaction tests, keyboard pass, Impeccable audit | Not implemented |
| NFR-008 | Responsive usability | Viewport checks (mobile/tablet/desktop) | Not implemented |
| NFR-009 | Measured performance baseline | ROAD-029 | Not implemented |
| NFR-010 | Data integrity constraints | QA-005, QA-025, DB integration tests | Executed and passed: DB constraints (`SchemaConstraintsTests`) + atomic/locked/idempotent stock events (`PantryTests`, incl. 10-way and 8-way concurrency) |
| NFR-011 | Honest test status | This file + PROGRESS.md | In effect (process) |
| NFR-012 | CI without deployment | QA-024; workflow review | Not implemented |

## QA → requirement map (Complete §9.3, with S-4 correction)

QA-001–002→FR-001 · QA-003→FR-002/FR-018 · QA-004→FR-003/FR-004/FR-024 · QA-005→FR-005 · QA-006→FR-005/FR-019 · QA-007→FR-006/FR-008 · QA-008→FR-007 · QA-009→FR-009/FR-010/FR-016 · QA-010→FR-011/FR-012 · QA-011→FR-015 · QA-012→FR-017/FR-018 · QA-013→FR-020 · QA-014→FR-021/FR-022 · QA-015→FR-013 · QA-016→FR-014 · QA-017→NFR-005 · QA-018→NFR-003 (spec wrote "BR-011", undefined) · QA-019–023→§8 security controls · QA-024→FR-026/NFR-012 · QA-025→FR-010/NFR-010 · QA-026→FR-016.

## Out of scope (Confirmed)

Cloud deployment/hosting/release/publishing/infrastructure · native mobile app · barcode scanning · email/push/SMS notifications · mandatory paid services · admin UI, shared households, public landing/onboarding, recipe authoring.
