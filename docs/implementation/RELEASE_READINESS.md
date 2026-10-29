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

- **Allergen safety:** allergen flags come from the sources plus `database/reference/ingredient_allergens.json`, which still needs owner review (D-031). Recipes with incomplete allergen data are excluded from suggestions and plans, so only 722 of the 4,067 recipes in the Loop 7 import were allergen-complete. The app never claims a recipe is safe.
- **Nutrition:** no recipe has nutrition data, so the weekly summary reports "unknown" (D-040).
- **Waste-risk prediction:** always answers "insufficient data" until real usage history exists (B-03, D-042).
- **Cuisine classifier:** test macro-F1 0.5156 (D-041); no acceptance threshold has been agreed yet (TBD-027).
- **Meal-plan start date:** plans start on the day they are generated, the proposed default (A-10).
- **Sessions:** the access token is kept in memory only, so reloading the page signs you out (D-008).
- **End-to-end tests:** the repeatable journey test drives the real frontend against a fake backend; the full stack was exercised by hand in the browser (Loop 11) and by the API baseline (Loop 13). No automated browser test runs against the real stack.
- **Dependency scanning:** the OSV-Scanner check runs only in CI and does not cover test-scope Java dependencies.
- **Performance figures** come from one Windows laptop with other projects running; they are a local baseline, not a service-level target.

## Open decisions for the owner

| ID | Decision |
|---|---|
| D-031 | Review and approve the ingredient allergen dictionary |
| A-10 | Confirm the meal-plan start date (generation day in use) |
| TBD-027 | Set an acceptance threshold for the cuisine classifier |
