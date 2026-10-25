-- PantryChef core schema v1. Numeric checks exclude 'NaN', which PostgreSQL orders above every number. Vocabularies follow decisions A-04 (categories/units) and A-08 (diets/allergens).
-- recipe_chunks (pgvector) is added in a later migration once the embedding model and dimension are approved.

CREATE DOMAIN category_code AS text CHECK (VALUE IN (
  'dairy', 'vegetables', 'fruits', 'grains', 'meat', 'seafood', 'bakery',
  'beverages', 'condiments', 'frozen', 'snacks', 'other'));

CREATE DOMAIN unit_code AS text CHECK (VALUE IN (
  'g', 'kg', 'ml', 'l', 'piece', 'pack', 'bunch', 'dozen'));

CREATE DOMAIN diet_code AS text CHECK (VALUE IN ('vegetarian', 'vegan', 'gluten_free'));

CREATE DOMAIN allergen_code AS text CHECK (VALUE IN (
  'peanuts', 'tree_nuts', 'dairy', 'eggs', 'gluten', 'soy', 'fish', 'shellfish', 'sesame'));

CREATE TABLE users (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email         text NOT NULL UNIQUE CHECK (email = lower(btrim(email)) AND length(email) BETWEEN 3 AND 254),
  password_hash text NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);

-- Remaining stock is derived from events; no status column (active = quantity_remaining > 0 AND deleted_at IS NULL).
CREATE TABLE pantry_items (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id            uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  name               text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 100),
  category           category_code NOT NULL,
  quantity_initial   numeric(12, 3) NOT NULL CHECK (quantity_initial > 0 AND quantity_initial <> 'NaN'),
  quantity_remaining numeric(12, 3) NOT NULL CHECK (quantity_remaining >= 0 AND quantity_remaining <> 'NaN'),
  unit               unit_code NOT NULL,
  purchase_price     numeric(12, 2) CHECK (purchase_price >= 0 AND purchase_price <> 'NaN'), -- total price paid for quantity_initial (A-03)
  currency_code      char(3) NOT NULL CHECK (currency_code ~ '^[A-Z]{3}$'),
  purchase_date      date,
  expiry_date        date, -- NULL = no expiry date, which is not the same as expired
  version            bigint NOT NULL DEFAULT 0,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  deleted_at         timestamptz, -- soft delete (A-05); never counts as use or savings
  CHECK (quantity_remaining <= quantity_initial),
  CHECK (expiry_date IS NULL OR purchase_date IS NULL OR expiry_date >= purchase_date)
);
CREATE INDEX pantry_items_user_expiry_idx ON pantry_items (user_id, expiry_date) WHERE deleted_at IS NULL;

CREATE TABLE pantry_item_events (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  pantry_item_id  uuid NOT NULL REFERENCES pantry_items (id) ON DELETE CASCADE,
  event_type      text NOT NULL CHECK (event_type IN ('used', 'finished', 'discarded')),
  quantity        numeric(12, 3) NOT NULL CHECK (quantity > 0 AND quantity <> 'NaN'),
  unit            unit_code NOT NULL, -- item's unit at event time, keeps history self-describing
  occurred_at     timestamptz NOT NULL DEFAULT now(),
  notes           text CHECK (length(notes) <= 500),
  idempotency_key text CHECK (length(idempotency_key) BETWEEN 1 AND 100),
  UNIQUE (user_id, idempotency_key)
);
CREATE INDEX pantry_item_events_item_idx ON pantry_item_events (pantry_item_id);
CREATE INDEX pantry_item_events_user_time_idx ON pantry_item_events (user_id, occurred_at);

-- One open alert per item; urgency is ordered by the item's expiry_date.
CREATE TABLE expiry_alerts (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id        uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  pantry_item_id uuid NOT NULL REFERENCES pantry_items (id) ON DELETE CASCADE,
  alert_type     text NOT NULL CHECK (alert_type IN ('expiring_soon', 'expired')),
  alert_date     date NOT NULL, -- scheduler run date (APP_TIMEZONE) that last evaluated the alert
  resolved_at    timestamptz,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX expiry_alerts_one_open_per_item ON expiry_alerts (pantry_item_id) WHERE resolved_at IS NULL;
CREATE INDEX expiry_alerts_user_open_idx ON expiry_alerts (user_id) WHERE resolved_at IS NULL;

CREATE TABLE user_dietary_preferences (
  user_id         uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  preference_code diet_code NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, preference_code)
);

CREATE TABLE user_allergens (
  user_id       uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  allergen_code allergen_code NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, allergen_code)
);

-- Shared reference data imported from a licensed source; unknown nutrition stays NULL.
CREATE TABLE recipes (
  id                         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  source_name                text NOT NULL,
  source_key                 text NOT NULL,
  source_url                 text,
  source_license             text NOT NULL,
  dataset_version            text NOT NULL,
  title                      text NOT NULL CHECK (length(btrim(title)) > 0),
  instructions               text,
  cuisine                    text,
  servings                   numeric(6, 2) CHECK (servings > 0 AND servings <> 'NaN'),
  calories_per_serving       numeric(10, 2) CHECK (calories_per_serving >= 0 AND calories_per_serving <> 'NaN'),
  protein_g_per_serving      numeric(10, 2) CHECK (protein_g_per_serving >= 0 AND protein_g_per_serving <> 'NaN'),
  carbs_g_per_serving        numeric(10, 2) CHECK (carbs_g_per_serving >= 0 AND carbs_g_per_serving <> 'NaN'),
  fat_g_per_serving          numeric(10, 2) CHECK (fat_g_per_serving >= 0 AND fat_g_per_serving <> 'NaN'),
  nutrition_source           text,
  diet_labels                text[] NOT NULL DEFAULT '{}'
                               CHECK (diet_labels <@ ARRAY['vegetarian', 'vegan', 'gluten_free']),
  allergen_metadata_complete boolean NOT NULL DEFAULT false, -- false = allergen status unknown, never "safe"
  created_at                 timestamptz NOT NULL DEFAULT now(),
  updated_at                 timestamptz NOT NULL DEFAULT now(),
  UNIQUE (source_name, source_key)
);

CREATE TABLE recipe_ingredients (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  recipe_id             uuid NOT NULL REFERENCES recipes (id) ON DELETE CASCADE,
  ingredient_name       text NOT NULL CHECK (length(btrim(ingredient_name)) > 0),
  normalized_ingredient text NOT NULL CHECK (length(btrim(normalized_ingredient)) > 0),
  quantity              numeric(12, 3) CHECK (quantity > 0 AND quantity <> 'NaN'),
  unit                  unit_code, -- NULL when the source unit is missing or not in the approved vocabulary
  allergen_flags        text[] NOT NULL DEFAULT '{}' CHECK (allergen_flags <@ ARRAY[
                          'peanuts', 'tree_nuts', 'dairy', 'eggs', 'gluten', 'soy', 'fish', 'shellfish', 'sesame']),
  sort_order            integer NOT NULL CHECK (sort_order >= 0),
  UNIQUE (recipe_id, sort_order)
);
CREATE INDEX recipe_ingredients_normalized_idx ON recipe_ingredients (normalized_ingredient);

CREATE TABLE meal_plans (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id            uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  start_date         date NOT NULL,
  end_date           date NOT NULL,
  status             text NOT NULL CHECK (status IN ('complete', 'partial')),
  generation_version text,
  idempotency_key    text CHECK (length(idempotency_key) BETWEEN 1 AND 100),
  created_at         timestamptz NOT NULL DEFAULT now(),
  CHECK (end_date = start_date + 6),
  UNIQUE (user_id, idempotency_key)
);
CREATE INDEX meal_plans_user_idx ON meal_plans (user_id, created_at);

-- A slot either has a recipe and servings, or is explicitly unavailable with a reason.
CREATE TABLE meal_plan_entries (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  meal_plan_id       uuid NOT NULL REFERENCES meal_plans (id) ON DELETE CASCADE,
  recipe_id          uuid REFERENCES recipes (id),
  plan_date          date NOT NULL,
  meal_period        text NOT NULL CHECK (meal_period IN ('breakfast', 'lunch', 'dinner')),
  servings           numeric(6, 2) CHECK (servings > 0 AND servings <> 'NaN'),
  unavailable_reason text CHECK (length(unavailable_reason) <= 300),
  UNIQUE (meal_plan_id, plan_date, meal_period),
  CHECK ((recipe_id IS NOT NULL AND servings IS NOT NULL AND unavailable_reason IS NULL)
      OR (recipe_id IS NULL AND servings IS NULL AND unavailable_reason IS NOT NULL))
);

CREATE TABLE grocery_items (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  meal_plan_id          uuid NOT NULL REFERENCES meal_plans (id) ON DELETE CASCADE,
  normalized_ingredient text NOT NULL,
  display_name          text NOT NULL,
  quantity_needed       numeric(12, 3) CHECK (quantity_needed > 0 AND quantity_needed <> 'NaN'),
  unit                  unit_code,
  quantity_unresolved   boolean NOT NULL DEFAULT false, -- true when units could not be safely combined/subtracted
  checked               boolean NOT NULL DEFAULT false,
  checked_at            timestamptz,
  CHECK (quantity_unresolved OR (quantity_needed IS NOT NULL AND unit IS NOT NULL)),
  CHECK (checked = (checked_at IS NOT NULL)),
  UNIQUE NULLS NOT DISTINCT (meal_plan_id, normalized_ingredient, unit)
);
