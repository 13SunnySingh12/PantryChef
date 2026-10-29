# PantryChef — Release-Readiness Checklist (ROAD-034)

Reviewed 2026-10-29 against the code on `main`. "Release" here means a verified local build; deployment is out of scope.

## Checklist

| Item | Status | Evidence |
|---|---|---|
| All P0 functional requirements | Executed and passed, except where noted below | [REQUIREMENT_TRACEABILITY.md](REQUIREMENT_TRACEABILITY.md) |
| Clean setup from GitHub (ROAD-033) | Executed and passed | Fresh clone: every module check passed; README steps started the DB, backend and AI service and imported 4,068 recipes ([PROGRESS.md](PROGRESS.md), Loop 13) |
| CI on push and pull request, no deploy (FR-026, NFR-012) | Executed and passed | Loop 12 deliberate-failure PR; latest `main` runs green |
| Dependency scans | In CI | `npm audit`, `pip-audit` (plus torch base version), OSV-Scanner on the backend's resolved dependencies (D-045) |
| Security review (QA-019–023, ROAD-024) | Executed and passed | Parameterised SQL, XSS as text, token/ownership tests, internal-service auth, read-only AI role, body-size cap and closed CORS (D-047) |
| Secrets (QA-023) | Executed and passed | No secrets in tracked files, git history or recent CI logs; `.env` ignored; `.env.example` holds placeholders only |
| Performance baseline (NFR-009) | Executed and passed | Loop 13 table; one bottleneck found and fixed (D-046) |
| Honest status (NFR-011) | In effect | Every check above links to a recorded run |
| Licensing and attribution of data | In effect | Wikibooks Cookbook (CC BY-SA 4.0) and TheMealDB (credited) shown per recipe; USDA not used (D-040) |
| No tool branding | Executed and passed | Attribution scans before every commit; global commit hooks |

## Known limitations

- **Allergen safety:** flags come from the sources plus `database/reference/ingredient_allergens.json` (approved 2026-10-29, D-031; flags mean contains or may contain). Recipes with incomplete allergen data are excluded for users with allergen exclusions, so only about 728 of 4,067 recipes are eligible for them. Stored recipes pick up dictionary changes only after a re-import. Cross-contact is not modelled, and the app never claims a recipe is safe.
- **Nutrition:** no recipe has nutrition data, so the weekly summary reports "unknown" (D-040).
- **Waste-risk prediction:** always answers "insufficient data" until real usage history exists (B-03, D-042).
- **Cuisine classifier:** passes its provisional pass mark (0.5872 against 0.56, D-048) by less than one standard deviation; the held-out split alone scores 0.5326. The mark is derived from a baseline, not from a product requirement. Filipino has 0 F1 on 4 test examples.
- **Meal-plan start date:** today by default, or up to 7 days ahead (A-10). The plan uses the pantry as it will be on the start date but does not model items expiring during the week.
- **Sessions:** the access token is kept in memory only, so reloading the page signs you out (D-008).
- **End-to-end tests:** the repeatable journey test drives the real frontend against a fake backend; the full stack was exercised by hand in the browser (Loop 11) and by the API baseline (Loop 13). No automated browser test runs against the real stack.
- **Dependency scanning:** the OSV-Scanner check runs only in CI and does not cover test-scope Java dependencies.
- **Performance figures** come from one Windows laptop with other projects running; they are a local baseline, not a service-level target.

## Open decisions for the owner

| ID | Decision |
|---|---|
| D-048 | Replace the provisional cuisine pass mark with a product requirement when one exists |
| B-03 | Waste-risk model and its threshold: blocked until real usage history exists |
