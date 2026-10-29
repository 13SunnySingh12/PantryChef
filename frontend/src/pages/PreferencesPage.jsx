import { useState } from 'react';
import { api } from '../api.js';
import { ErrorNotice, Loading } from '../ui.jsx';
import { useLoad, useToast } from '../shared.js';

const DIETS = [['vegetarian', 'Vegetarian'], ['vegan', 'Vegan'], ['gluten_free', 'Gluten-free']];
const ALLERGENS = [['peanuts', 'Peanuts'], ['tree_nuts', 'Tree nuts'], ['dairy', 'Dairy'], ['eggs', 'Eggs'],
  ['gluten', 'Gluten'], ['soy', 'Soy'], ['fish', 'Fish'], ['shellfish', 'Shellfish'], ['sesame', 'Sesame']];

export default function PreferencesPage() {
  const { data, error: loadError, loading } = useLoad(() => api('/preferences'), 'preferences');
  return (
    <div>
      <div className="page-head">
        <div>
          <h1>Diet and allergies</h1>
          <p className="muted">Applied to every recipe suggestion and meal plan.</p>
        </div>
      </div>
      <ErrorNotice error={loadError} />
      {loading && !data && <Loading rows={2} />}
      {data && <PreferencesForm initial={data} />}
    </div>
  );
}

function PreferencesForm({ initial }) {
  const [diets, setDiets] = useState(initial.diets);
  const [allergens, setAllergens] = useState(initial.allergens);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const toast = useToast();

  const toggle = (list, setList, code) => (e) =>
    setList(e.target.checked ? [...list, code] : list.filter((c) => c !== code));

  async function save(event) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const saved = await api('/preferences', { method: 'PUT', body: { diets, allergens } });
      setDiets(saved.diets);
      setAllergens(saved.allergens);
      toast('Preferences saved. Suggestions and new meal plans use them.');
    } catch (e) {
      setError(e);
    }
    setBusy(false);
  }

  return (
        <form className="stack" onSubmit={save}>
          <fieldset className="panel stack">
            <legend className="legend-heading">Diet</legend>
            <div className="checks">
              {DIETS.map(([code, name]) => (
                <label key={code} className="check">
                  <input type="checkbox" checked={diets.includes(code)} onChange={toggle(diets, setDiets, code)} />
                  {name}
                </label>
              ))}
            </div>
          </fieldset>
          <fieldset className="panel stack">
            <legend className="legend-heading">Allergies to avoid</legend>
            <div className="checks">
              {ALLERGENS.map(([code, name]) => (
                <label key={code} className="check">
                  <input type="checkbox" checked={allergens.includes(code)}
                    onChange={toggle(allergens, setAllergens, code)} />
                  {name}
                </label>
              ))}
            </div>
            <p className="small muted">
              When you avoid an allergen, recipes are only shown if every ingredient is on PantryChef's checked
              ingredient list and none of them contains it. Recipes with unknown ingredients are left out. The data
              can still be incomplete and is not medical advice: always check ingredient labels.
            </p>
          </fieldset>
          <ErrorNotice error={error} />
          <div><button type="submit" className="btn btn-primary" disabled={busy}>Save preferences</button></div>
        </form>
  );
}
