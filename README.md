# PantryChef

Cook with what you already have. Waste less food, save more money.

Zero-waste meal planner: pantry and expiry tracking, in-app expiry alerts, pantry-based recipe suggestions, a weekly meal plan with grocery list, and a savings/nutrition dashboard. Runs locally; deployment is out of scope.

Specifications: [PantryChef.md](PantryChef.md) (overview) and [PantryChef_Complete.md](PantryChef_Complete.md) (full spec). Implementation status: [docs/implementation/](docs/implementation/PROGRESS.md); known limitations and open decisions: [RELEASE_READINESS.md](docs/implementation/RELEASE_READINESS.md).

## Layout

| Path | What | Stack |
|---|---|---|
| `frontend/` | Web UI; talks only to the backend | React (JavaScript), Vite, Vitest, oxlint |
| `backend/` | Core API: auth, pantry, alerts, dashboard | Spring Boot 4.1, Java 21, Maven Wrapper |
| `ai-service/` | Internal AI service: recipe retrieval, ML, meal planning | FastAPI, Python 3.12 |

## Prerequisites

Node.js 24, JDK 21, Python 3.12, Docker (local database and backend tests).

## Local database

```bash
cp .env.example .env
```

Edit `.env` and set your own local passwords and a `JWT_SIGNING_SECRET` of at least 32 random bytes (the file is git-ignored; the backend refuses to start without the secret). Then start PostgreSQL 17 + pgvector on `127.0.0.1:5440`:

```bash
docker compose up -d
```

The backend applies migrations from `database/migrations/` (Flyway) on startup. Backend tests start their own throwaway database container, so they need Docker running but no `.env`.

## Check each module

Frontend:

```bash
cd frontend && npm ci && npm run lint && npm test && npm run build
```

Backend (Windows: `mvnw.cmd -B verify`):

```bash
cd backend && ./mvnw -B verify
```

AI service (Windows: `.venv\Scripts\python`):

```bash
cd ai-service && python -m venv .venv && .venv/bin/python -m pip install -r requirements.txt && .venv/bin/python -m pytest -q
```

## Recipe data (one-time, after the backend has created the schema)

Recipes come from the Wikibooks Cookbook (CC BY-SA 4.0) and TheMealDB (development/educational test key); see
[docs/implementation/DATA_SOURCE_OPTIONS.md](docs/implementation/DATA_SOURCE_OPTIONS.md). Raw downloads stay in
`ai-service/data/` (git-ignored). The AI service reads the database as a read-only role, `pantrychef_ai`. The
migration creates it without a password, so give it one locally. Use the same value as in `AI_DATABASE_URL` in your
`.env`:

```bash
docker compose exec db psql -U pantrychef -d pantrychef -c "ALTER ROLE pantrychef_ai LOGIN PASSWORD 'your-local-password'"
```

Then download the sources (about 4 minutes) and import them. The import downloads the embedding model
`sentence-transformers/all-MiniLM-L6-v2` (about 91 MB) once, and needs `INGEST_DATABASE_URL` in `.env`:

```bash
cd ai-service && .venv/bin/python scripts/fetch_sources.py && .venv/bin/python scripts/import_recipes.py
```

Re-running the import is safe: unchanged recipes are not re-embedded.

To train the cuisine classifier from the same downloads (about 30 seconds; the model file stays in the git-ignored
`ai-service/models/`, and its metrics card is copied to `ai-service/model_cards/cuisine.json`):

```bash
cd ai-service && .venv/bin/python scripts/train_cuisine.py
```

Without a trained model, cuisine classification answers "not available"; the waste-risk estimate always answers
"insufficient data" until real usage history exists.

## Run locally

- Frontend: `cd frontend && npm run dev` (http://localhost:5173; `/api` is forwarded to the backend on port 8080, or to `PANTRYCHEF_BACKEND_URL`)
- Backend (after `docker compose up -d`): `cd backend && ./mvnw spring-boot:run`
- AI service (needs `AI_DATABASE_URL` and `SERVICE_TO_SERVICE_SECRET`; the backend needs the same secret): `cd ai-service && .venv/bin/python -m uvicorn app.main:app --port 8000`

Signing in keeps the access token in memory only, so reloading the page signs you out (decision D-008).

Allergen and diet information is derived from the recipe sources plus a reviewed ingredient list
(`database/reference/ingredient_allergens.json`). It can be incomplete, and PantryChef does not guarantee that a recipe
is safe for an allergy.

CI (`.github/workflows/ci.yml`) runs these checks plus `npm audit`, `pip-audit` and an OSV-Scanner check of the backend's resolved dependencies on every push to `main` and every pull request.
