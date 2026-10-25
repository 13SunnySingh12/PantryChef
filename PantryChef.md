# 🥗 PantryChef: Zero-Waste Meal Planner

> Cook with what you already have. Waste less food, save more money.

## Problem

People throw away food because they don't know what to cook with what they have at home. Items expire unnoticed, and meal planning feels like a chore.

## Solution

PantryChef tracks your pantry and expiry dates, alerts you before food goes bad, and suggests recipes that use what you already own. It also predicts what is likely to be wasted and builds a weekly meal plan and grocery list around it.

---

## Tech Stack

| Layer | Technology | Purpose |
|---|---|---|
| Frontend | JavaScript + React (no TypeScript) | UI, pantry screens, dashboard |
| Core Backend | Spring Boot (Java) | Auth, pantry CRUD, expiry alerts, dashboard stats |
| AI Service | FastAPI (Python) | RAG, ML models, meal plan generation |
| Database | Supabase (serverless PostgreSQL) + pgvector | App data and recipe embeddings |
| ML | scikit-learn | Cuisine classifier, waste predictor |
| RAG | sentence-transformers + pgvector | Recipe search with citations |
| CI/CD | GitHub Actions | Build and test on every push (no deployment) |

### How the pieces fit

```
React  ──►  Spring Boot  ──►  Supabase (Postgres)
              │                    ▲
              └──►  FastAPI  ──────┘
                  (RAG + ML)
```

- React talks only to Spring Boot.
- Spring Boot handles normal app logic and calls FastAPI for anything AI-related.
- Both services share the same Supabase database.

---

## Complete Features

### 1. Pantry Inventory
- Add, edit, and delete pantry items (name, quantity, unit, price, purchase date, expiry date)
- Categorize items (dairy, vegetables, grains, etc.)
- Mark items as used, finished, or thrown away

### 2. Expiry Alerts (Spring Boot)
- Scheduled daily job finds items expiring in the next 3 days
- Alerts shown in the app, sorted by urgency
- Expired items flagged automatically

### 3. Recipe Suggestions with RAG (FastAPI)
- Recipe dataset is chunked and embedded once, stored in Supabase with pgvector
- "Cook with what I have": the user's pantry items are used to retrieve the best matching recipes
- Items close to expiry are ranked higher
- Every suggestion cites its source recipe (title, ingredients matched, missing ingredients)

### 4. ML Models (FastAPI)
- **Cuisine classifier:** predicts cuisine (Indian, Italian, Mexican, etc.) from a list of ingredients, using TF-IDF + Logistic Regression
- **Waste predictor:** answers "will this item be wasted?" from purchase and usage history, using Random Forest or Logistic Regression on features like days to expiry, quantity, category, and past usage rate

### 5. Dietary Filters
- Vegan, vegetarian, gluten-free, and similar diet options
- Allergy list (nuts, dairy, etc.) that excludes unsafe recipes everywhere, including RAG results and the weekly plan

### 6. Weekly Meal Plan and Grocery List
- One click generates a 7-day plan (breakfast, lunch, dinner)
- Plan prioritizes expiring items and respects dietary filters
- Grocery list is built from the ingredients the plan needs minus what is already in the pantry
- Grocery items can be checked off

### 7. Dashboard
- **Waste saved:** items and quantity used before expiry
- **Money saved:** value of food rescued from the bin
- **Nutrition summary:** weekly calories, protein, carbs, and fat from the planned meals
- Simple charts (bar and line) using a React chart library

### 8. User Accounts
- Register and login with JWT authentication
- Each user sees only their own pantry, plans, and stats

### 9. CI/CD (GitHub Actions)
- Frontend: install, lint, build
- Spring Boot: build and run unit tests
- FastAPI: install, run pytest
- Runs on every push and pull request; no deployment step

---

## Suggested Build Order

1. Supabase database and tables (users, pantry_items, recipes, meal_plans)
2. Spring Boot: auth and pantry CRUD
3. Expiry alert scheduler
4. React: login, pantry list, add item form
5. FastAPI: load recipes, create embeddings, RAG endpoint
6. Train the cuisine classifier and waste predictor, expose as endpoints
7. Dietary filters, weekly plan, grocery list
8. Dashboard
9. GitHub Actions workflows

---

## Out of Scope (to keep it simple)

- Cloud deployment
- Mobile app
- Barcode scanning
- Email or push notifications (in-app alerts only)
