import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { ArrowSquareOut } from '@phosphor-icons/react';
import { api, newRequestKey } from '../api.js';
import { Empty, ErrorNotice, Loading } from '../ui.jsx';
import { formatAmount, formatQuantity, useLoad, useToast } from '../shared.js';

const PERIODS = ['breakfast', 'lunch', 'dinner'];
const dayName = (date) => new Date(`${date}T00:00:00`).toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'short' });

export default function PlansPage() {
  const { planId } = useParams();
  const { data: plans, error, loading, reload } = useLoad(() => api('/meal-plans'), 'plans');
  const [busy, setBusy] = useState(false);
  const [generateError, setGenerateError] = useState(null);
  const [key, setKey] = useState(newRequestKey);
  const navigate = useNavigate();
  const shownId = planId ?? plans?.[0]?.id;

  async function generate() {
    setBusy(true);
    setGenerateError(null);
    try {
      const plan = await api('/meal-plans', { method: 'POST', headers: { 'Idempotency-Key': key } });
      setKey(newRequestKey());
      await reload();
      navigate(`/plans/${plan.id}`);
    } catch (e) {
      setGenerateError(e);
    }
    setBusy(false);
  }

  return (
    <div className="stack" style={{ gap: 24 }}>
      <div className="page-head">
        <div>
          <h1>Meal plan</h1>
          <p className="muted">Seven days of breakfast, lunch and dinner, using food that expires soon first.</p>
        </div>
        <button type="button" className="btn btn-primary" onClick={generate} disabled={busy}>
          {busy ? 'Planning' : 'Plan the next 7 days'}
        </button>
      </div>
      <ErrorNotice error={error || generateError} />
      {loading && !plans && <Loading />}
      {plans && plans.length === 0 && (
        <Empty title="No meal plan yet">
          <p>Create one to get a week of meals and a grocery list.</p>
        </Empty>
      )}
      {plans && plans.length > 1 && (
        <nav aria-label="Earlier plans" className="row-meta">
          <span>Plans:</span>
          {plans.map((p) => (
            <Link key={p.id} to={`/plans/${p.id}`} aria-current={p.id === shownId ? 'page' : undefined}>
              {p.startDate} ({p.plannedSlots}/21 meals)
            </Link>
          ))}
        </nav>
      )}
      {shownId && <PlanView key={shownId} planId={shownId} />}
    </div>
  );
}

function PlanView({ planId }) {
  const { data: loaded, error, loading } = useLoad(() => api(`/meal-plans/${planId}`), `plan:${planId}`);
  const [plan, setPlan] = useState(null);
  const current = plan ?? loaded;
  if (loading && !current) return <Loading rows={4} label="Loading plan" />;
  if (error) return <ErrorNotice error={error} />;
  if (!current) return null;

  const days = [...new Set(current.entries.map((e) => e.date))];
  const missing = current.entries.filter((e) => !e.recipeId).length;
  return (
    <>
      <section className="stack" aria-labelledby="week-heading">
        <div>
          <h2 id="week-heading">Week of {dayName(current.startDate)}</h2>
          <p className="small muted">
            {missing === 0 ? 'Every meal is planned.' : `${missing} of 21 meals could not be planned; the reason is shown in each slot.`}
          </p>
        </div>
        <div className="week">
          {days.map((date) => (
            <article key={date} className="panel day">
              <h3>{dayName(date)}</h3>
              <dl>
                {PERIODS.map((period) => {
                  const entry = current.entries.find((e) => e.date === date && e.mealPeriod === period);
                  return (
                    <div key={period} className="meal">
                      <dt>{period.charAt(0).toUpperCase() + period.slice(1)}</dt>
                      <dd>
                        {entry?.recipeId ? (
                          <>
                            <a href={entry.sourceUrl} target="_blank" rel="noopener noreferrer">
                              {entry.title}
                              <ArrowSquareOut size={14} aria-hidden="true" />
                              <span className="visually-hidden"> (opens the source in a new tab)</span>
                            </a>
                            <span className="small muted">
                              {entry.servings ? ` Serves ${formatQuantity(entry.servings)}.` : ' Servings not stated.'}
                            </span>
                          </>
                        ) : (
                          <span className="muted">No meal: {entry?.unavailableReason ?? 'not planned'}</span>
                        )}
                      </dd>
                    </div>
                  );
                })}
              </dl>
            </article>
          ))}
        </div>
        <p className="small muted">{current.disclaimer}</p>
      </section>
      <Grocery plan={current} onChange={setPlan} />
    </>
  );
}

function Grocery({ plan, onChange }) {
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const toast = useToast();
  const unresolved = plan.grocery.filter((g) => g.unresolved).length;

  async function toggle(item) {
    setError(null);
    const previous = plan;
    onChange({ ...plan, grocery: plan.grocery.map((g) => (g.id === item.id ? { ...g, checked: !g.checked } : g)) });
    try {
      await api(`/grocery-items/${item.id}`, { method: 'PATCH', body: { checked: !item.checked } });
    } catch (e) {
      onChange(previous);
      setError(e);
    }
  }

  async function recalculate() {
    setBusy(true);
    setError(null);
    try {
      onChange(await api(`/meal-plans/${plan.id}/grocery-recalculation`, { method: 'POST' }));
      toast('Grocery list updated from your current pantry.');
    } catch (e) {
      setError(e);
    }
    setBusy(false);
  }

  return (
    <section className="panel stack" aria-labelledby="grocery-heading">
      <div className="page-head" style={{ marginBottom: 0 }}>
        <div>
          <h2 id="grocery-heading">Grocery list</h2>
          <p className="small muted">What the plan needs, minus what is already in your pantry.</p>
        </div>
        <button type="button" className="btn" onClick={recalculate} disabled={busy}>Recalculate from pantry</button>
      </div>
      <ErrorNotice error={error} />
      {plan.grocery.length === 0 ? (
        <p className="muted">Nothing to buy: your pantry covers the plan.</p>
      ) : (
        <ul className="grocery">
          {plan.grocery.map((item) => (
            <li key={item.id}>
              <label className="check grocery-item">
                <input type="checkbox" checked={item.checked} onChange={() => toggle(item)} />
                <span className={item.checked ? 'done' : undefined}>{item.ingredient}</span>
                <span className="small muted num">
                  {item.unresolved ? 'amount not calculated' : `${formatAmount(item.quantity)} ${item.unit}`}
                </span>
              </label>
            </li>
          ))}
        </ul>
      )}
      {unresolved > 0 && (
        <p className="small muted">
          {unresolved === 1 ? '1 item has' : `${unresolved} items have`} no amount because the recipes give them in units that cannot be added up safely
          (for example a pinch, or the same food by weight in one recipe and by volume in another).
        </p>
      )}
    </section>
  );
}
