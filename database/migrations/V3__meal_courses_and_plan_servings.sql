-- Meal planning (L8). A recipe's course comes from its source categories; NULL means the source gave none, and such
-- recipes are not used to fill plan slots. A planned slot may have unknown servings when the source states none
-- (the recipe is then cooked as written); servings are never invented.

ALTER TABLE recipes ADD COLUMN course text
  CHECK (course IN ('breakfast', 'main', 'side', 'dessert', 'drink', 'condiment'));

DO $$
DECLARE
  slot_check text;
BEGIN
  SELECT conname INTO slot_check FROM pg_constraint
   WHERE conrelid = 'meal_plan_entries'::regclass AND contype = 'c'
     AND pg_get_constraintdef(oid) LIKE '%unavailable_reason IS NOT NULL%';
  EXECUTE format('ALTER TABLE meal_plan_entries DROP CONSTRAINT %I', slot_check);
END
$$;

ALTER TABLE meal_plan_entries ADD CONSTRAINT meal_plan_entries_slot_check CHECK (
  (recipe_id IS NOT NULL AND unavailable_reason IS NULL)
  OR (recipe_id IS NULL AND servings IS NULL AND unavailable_reason IS NOT NULL));

-- When the grocery list was last computed from the pantry (generation or explicit recalculation).
ALTER TABLE meal_plans ADD COLUMN grocery_calculated_at timestamptz;
