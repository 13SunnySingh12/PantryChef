import { useState } from 'react';
import { Link } from 'react-router';
import { ArrowSquareOut, Clock, Info } from '@phosphor-icons/react';
import { api } from '../api.js';
import { Empty, ErrorNotice, Field, Loading } from '../ui.jsx';
import { useLoad } from '../shared.js';

const REASONS = {
  empty_pantry: ['Your pantry is empty', 'Add some items to get recipe suggestions.'],
  no_usable_pantry_items: ['Nothing usable in your pantry', 'Everything left has passed its expiry date.'],
  no_safe_match: ['No recipe fits your settings',
    'Some recipes matched your pantry but were left out because of your diet or allergy settings.'],
  no_match: ['No matching recipes', 'None of the recipes use what is in your pantry yet.'],
};

const DIET_NAMES = { vegetarian: 'Vegetarian', vegan: 'Vegan', gluten_free: 'Gluten-free' };

export default function RecipesPage() {
  const { data, error, loading, reload } = useLoad(() => api('/recipes/suggestions?limit=12'), 'suggestions');
  const reason = data && data.suggestions.length === 0 ? REASONS[data.reason] ?? REASONS.no_match : null;

  return (
    <div className="stack" style={{ gap: 28 }}>
      <section>
        <div className="page-head">
          <div>
            <h1>Recipes from your pantry</h1>
            <p className="muted">Ranked by how much you already have, with food that expires soon first.</p>
          </div>
          <button type="button" className="btn" onClick={reload} disabled={loading}>Refresh</button>
        </div>
        <ErrorNotice error={error} />
        {loading && !data && <Loading rows={4} label="Finding recipes" />}
        {reason && (
          <Empty title={reason[0]}>
            <p>{reason[1]}</p>
            {data.reason === 'empty_pantry' && <Link to="/pantry" className="btn">Go to pantry</Link>}
            {data.reason === 'no_safe_match' && <Link to="/preferences" className="btn">Review settings</Link>}
          </Empty>
        )}
        {data && data.suggestions.length > 0 && (
          <ul className="rows" aria-label="Recipe suggestions">
            {data.suggestions.map((recipe) => <RecipeCard key={recipe.recipeId} recipe={recipe} />)}
          </ul>
        )}
        {data && (
          <p className="notice small" style={{ marginTop: 16 }}>
            <Info size={18} aria-hidden="true" style={{ flexShrink: 0 }} />
            <span>
              {data.disclaimer}
              {data.excludedByPreferences > 0 && ` ${data.excludedByPreferences} recipes were left out because of your settings.`}
            </span>
          </p>
        )}
      </section>
      <CuisineGuess />
    </div>
  );
}

function RecipeCard({ recipe }) {
  const { source } = recipe;
  return (
    <li className="panel stack" style={{ gap: 10 }}>
      <div className="stack" style={{ gap: 4 }}>
        <h2>
          <a href={source.url} target="_blank" rel="noopener noreferrer" className="recipe-link">
            {recipe.title}
            <ArrowSquareOut size={16} aria-hidden="true" />
            <span className="visually-hidden"> (opens the source in a new tab)</span>
          </a>
        </h2>
        <div className="row-meta">
          {recipe.usesExpiringItems.length > 0 && (
            <span className="badge badge-soon">
              <Clock size={14} weight="bold" aria-hidden="true" /> Uses {recipe.usesExpiringItems.join(', ')}
            </span>
          )}
          {recipe.cuisine && <span>{recipe.cuisine}</span>}
          {recipe.dietLabels.map((d) => <span key={d}>{DIET_NAMES[d] ?? d}</span>)}
          {!recipe.allergenDataComplete && <span>Allergen information incomplete</span>}
        </div>
      </div>
      <div className="grid-2">
        <div>
          <h3 className="small">You have ({recipe.matchedIngredients.length})</h3>
          <p className="small">{recipe.matchedIngredients.join(', ')}</p>
        </div>
        <div>
          <h3 className="small">Missing ({recipe.missingIngredients.length})</h3>
          <p className="small">{recipe.missingIngredients.join(', ') || 'Nothing'}</p>
        </div>
      </div>
      <p className="small muted">{source.attribution}</p>
    </li>
  );
}

function CuisineGuess() {
  const [text, setText] = useState('');
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      const ingredients = text.split(/[\n,]/).map((s) => s.trim()).filter(Boolean);
      setResult(await api('/cuisine/classify', { method: 'POST', body: { ingredients } }));
    } catch (e) {
      setError(e);
    }
    setBusy(false);
  }

  return (
    <section className="panel stack">
      <div>
        <h2>Which cuisine is this?</h2>
        <p className="small muted">An estimate from a model trained on the recipe collection. It can be wrong.</p>
      </div>
      <form className="stack" onSubmit={submit}>
        <Field label="Ingredients" hint="One per line, or separated by commas." error={error?.fieldErrors.ingredients}>
          {(props) => <textarea {...props} rows={3} value={text} onChange={(e) => setText(e.target.value)} />}
        </Field>
        <div><button type="submit" className="btn" disabled={busy || !text.trim()}>Estimate cuisine</button></div>
      </form>
      <ErrorNotice error={error && !error.fieldErrors.ingredients ? error : null} />
      {result && (
        <p role="status">
          {result.status === 'unknown'
            ? 'None of these ingredients are known to the model, so it cannot estimate a cuisine.'
            : <>Most likely <strong>{result.cuisine}</strong>{result.alternatives.length > 0 &&
                `, then ${result.alternatives.join(' or ')}`}.</>}
        </p>
      )}
    </section>
  );
}
