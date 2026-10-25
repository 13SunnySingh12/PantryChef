# PantryChef

Cook with what you already have. Waste less food, save more money.

Zero-waste meal planner: pantry and expiry tracking, in-app expiry alerts, pantry-based recipe suggestions, a weekly meal plan with grocery list, and a savings/nutrition dashboard. Runs locally; deployment is out of scope.

Specifications: [PantryChef.md](PantryChef.md) (overview) and [PantryChef_Complete.md](PantryChef_Complete.md) (full spec). Implementation status: [docs/implementation/](docs/implementation/PROGRESS.md).

## Layout

| Path | What | Stack |
|---|---|---|
| `frontend/` | Web UI; talks only to the backend | React (JavaScript), Vite, Vitest, oxlint |
| `backend/` | Core API: auth, pantry, alerts, dashboard | Spring Boot 4.1, Java 21, Maven Wrapper |
| `ai-service/` | Internal AI service: recipe retrieval, ML, meal planning | FastAPI, Python 3.12 |

## Prerequisites

Node.js 24, JDK 21, Python 3.12. (Docker is needed from the database loop onward.)

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

## Run locally

- Frontend: `cd frontend && npm run dev`
- Backend: `cd backend && ./mvnw spring-boot:run`
- AI service: `cd ai-service && .venv/bin/python -m uvicorn app.main:app --reload`

CI (`.github/workflows/ci.yml`) runs these checks plus `npm audit` and `pip-audit` on every push to `main` and every pull request.
