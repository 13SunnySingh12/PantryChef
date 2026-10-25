-- Recipe embeddings (B-02 approved: sentence-transformers/all-MiniLM-L6-v2, 384 dimensions) and a read-only role
-- for the AI service (D-010). A different model or dimension needs a new migration and a full re-index.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE recipe_chunks (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  recipe_id       uuid NOT NULL REFERENCES recipes (id) ON DELETE CASCADE,
  chunk_index     integer NOT NULL CHECK (chunk_index >= 0),
  chunk_text      text NOT NULL CHECK (length(chunk_text) > 0),
  embedding       vector(384) NOT NULL,
  embedding_model text NOT NULL,
  dataset_version text NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (recipe_id, chunk_index, embedding_model)
);

-- Attribution shown with every citation (CC BY-SA credit line, TheMealDB credit).
ALTER TABLE recipes ADD COLUMN attribution text;

-- The AI service reads recipe data only: no access to users, pantry, preferences or plans. It is created without
-- a password; a local setup step grants LOGIN with a password from the developer's .env (never in a migration).
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pantrychef_ai') THEN
    CREATE ROLE pantrychef_ai NOLOGIN;
  END IF;
END
$$;
GRANT USAGE ON SCHEMA public TO pantrychef_ai;
GRANT SELECT ON recipes, recipe_ingredients, recipe_chunks TO pantrychef_ai;
