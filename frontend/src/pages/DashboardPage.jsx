import { useState } from 'react';
import { Bar, BarChart, CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { Info } from '@phosphor-icons/react';
import { api } from '../api.js';
import { ErrorNotice, Loading } from '../ui.jsx';
import { formatAmount, plural, useLoad } from '../shared.js';

const WINDOWS = [7, 30, 90];
const shortDate = (date) => new Date(`${date}T00:00:00`).toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
const amounts = (list) => (list.length ? list.map((a) => `${formatAmount(a.amount)} ${a.unit}`).join(', ') : 'none');

export default function DashboardPage() {
  const [days, setDays] = useState(30);
  const { data, error, loading } = useLoad(() => api(`/dashboard/summary?days=${days}`), `summary:${days}`);

  return (
    <div className="stack" style={{ gap: 24 }}>
      <div className="page-head">
        <div>
          <h1>Dashboard</h1>
          <p className="muted">What you saved by using food before it expired.</p>
        </div>
        <div className="checks" role="group" aria-label="Time range">
          {WINDOWS.map((d) => (
            <button key={d} type="button" className="btn btn-small" aria-pressed={days === d}
              onClick={() => setDays(d)}>{d} days</button>
          ))}
        </div>
      </div>
      <ErrorNotice error={error} />
      {loading && !data && <Loading rows={3} />}
      {data && <Summary data={data} />}
    </div>
  );
}

function Summary({ data }) {
  const { foodSaved, thrownAway, moneySaved, currency } = data;
  const money = moneySaved.totals.find((t) => t.currency === currency);
  const money0 = new Intl.NumberFormat(undefined, { style: 'currency', currency });
  return (
    <>
      <section className="figures" aria-label="Totals">
        <div className="panel figure">
          <span className="small muted">Money saved</span>
          <strong className="num">{money0.format(money?.amount ?? 0)}</strong>
          {moneySaved.eventsWithUnknownPrice > 0 && (
            <span className="small muted">{plural(moneySaved.eventsWithUnknownPrice, 'use', 'uses')} without a price {moneySaved.eventsWithUnknownPrice === 1 ? 'is' : 'are'} not included</span>
          )}
        </div>
        <div className="panel figure">
          <span className="small muted">Food used before expiry</span>
          <strong className="num">{plural(foodSaved.items, 'item', 'items')}</strong>
          <span className="small muted num">{amounts(foodSaved.quantities)}</span>
        </div>
        <div className="panel figure">
          <span className="small muted">Thrown away</span>
          <strong className="num">{plural(thrownAway.items, 'item', 'items')}</strong>
          <span className="small muted num">{amounts(thrownAway.quantities)}</span>
        </div>
      </section>
      {(foodSaved.usedAfterExpiry > 0 || foodSaved.usedWithoutExpiryDate > 0) && (
        <p className="small muted">
          Not counted as saved: {plural(foodSaved.usedAfterExpiry, 'use', 'uses')} after the expiry date and{' '}
          {plural(foodSaved.usedWithoutExpiryDate, 'use', 'uses')} of items without an expiry date.
        </p>
      )}
      <MoneyChart daily={data.daily} currency={currency} format={money0.format} />
      <Nutrition nutrition={data.nutrition} />
      <p className="notice small">
        <Info size={18} aria-hidden="true" style={{ flexShrink: 0 }} />
        <span>{data.note}</span>
      </p>
    </>
  );
}

function MoneyChart({ daily, currency, format }) {
  const points = daily.map((d) => ({ ...d, label: shortDate(d.date), money: Number(d.moneySaved) }));
  const total = points.reduce((sum, p) => sum + p.money, 0);
  const best = points.reduce((a, b) => (b.money > a.money ? b : a), points[0]);
  return (
    <section className="panel stack" aria-labelledby="money-heading">
      <div>
        <h2 id="money-heading">Money saved per day ({currency})</h2>
        <p className="small muted">
          {total > 0 ? `${format(total)} over ${points.length} days; the best day was ${best.label} (${format(best.money)}).`
            : `No savings recorded in the last ${points.length} days yet.`}
        </p>
      </div>
      <div className="chart" aria-hidden="true">
        <ResponsiveContainer width="100%" height={220}>
          <LineChart data={points} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
            <CartesianGrid stroke="var(--border)" vertical={false} />
            <XAxis dataKey="label" tick={{ fill: 'var(--text-muted)', fontSize: 12 }} minTickGap={24} />
            <YAxis tick={{ fill: 'var(--text-muted)', fontSize: 12 }} width={56} />
            <Tooltip formatter={(v) => format(v)} contentStyle={{ background: 'var(--surface)', border: '1px solid var(--border)' }} />
            <Line type="monotone" dataKey="money" name="Saved" stroke="var(--accent)" strokeWidth={2} dot={false} isAnimationActive={false} />
          </LineChart>
        </ResponsiveContainer>
      </div>
      <details>
        <summary className="small">Show as a table</summary>
        <div className="table-wrap">
          <table>
            <thead><tr><th scope="col">Day</th><th scope="col">Money saved</th><th scope="col">Uses before expiry</th><th scope="col">Thrown away</th></tr></thead>
            <tbody>
              {points.filter((p) => p.savedEvents || p.discardedEvents).map((p) => (
                <tr key={p.date}><td>{p.label}</td><td className="num">{format(p.money)}</td>
                  <td className="num">{p.savedEvents}</td><td className="num">{p.discardedEvents}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </section>
  );
}

function Nutrition({ nutrition }) {
  if (!nutrition) {
    return (
      <section className="panel stack" aria-labelledby="nutrition-heading">
        <h2 id="nutrition-heading">Nutrition</h2>
        <p className="muted">Create a meal plan to see its nutrition here.</p>
      </section>
    );
  }
  const known = nutrition.days.filter((d) => d.total);
  return (
    <section className="panel stack" aria-labelledby="nutrition-heading">
      <div>
        <h2 id="nutrition-heading">Nutrition of your latest meal plan</h2>
        <p className="small muted">
          One serving per meal. {nutrition.mealsWithNutrition} of {nutrition.plannedMeals} planned meals have
          nutrition data.
        </p>
      </div>
      {known.length === 0 ? (
        <p className="muted">
          Unknown: the recipe sources do not include nutrition information, so PantryChef does not show figures
          rather than guess them.
        </p>
      ) : (
        <>
          <div className="chart" aria-hidden="true">
            <ResponsiveContainer width="100%" height={220}>
              <BarChart data={known.map((d) => ({ label: shortDate(d.date), calories: Number(d.total.calories) }))}>
                <CartesianGrid stroke="var(--border)" vertical={false} />
                <XAxis dataKey="label" tick={{ fill: 'var(--text-muted)', fontSize: 12 }} />
                <YAxis tick={{ fill: 'var(--text-muted)', fontSize: 12 }} width={56} />
                <Tooltip contentStyle={{ background: 'var(--surface)', border: '1px solid var(--border)' }} />
                <Bar dataKey="calories" name="Calories (known meals)" fill="var(--accent)" isAnimationActive={false} />
              </BarChart>
            </ResponsiveContainer>
          </div>
          <div className="table-wrap">
            <table>
              <thead><tr><th scope="col">Day</th><th scope="col">Meals with data</th><th scope="col">Calories</th><th scope="col">Protein (g)</th><th scope="col">Carbs (g)</th><th scope="col">Fat (g)</th></tr></thead>
              <tbody>
                {nutrition.days.map((d) => (
                  <tr key={d.date}>
                    <td>{shortDate(d.date)}</td>
                    <td className="num">{d.mealsWithNutrition} of {d.plannedMeals}</td>
                    {d.total ? ['calories', 'proteinG', 'carbsG', 'fatG'].map((k) => (
                      <td key={k} className="num">{formatAmount(d.total[k])}</td>
                    )) : <td colSpan={4} className="muted">unknown</td>}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </section>
  );
}
