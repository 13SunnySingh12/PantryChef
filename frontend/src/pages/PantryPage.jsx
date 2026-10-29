import { useState } from 'react';
import { Plus, WarningCircle, Clock, CheckCircle } from '@phosphor-icons/react';
import { api, newRequestKey } from '../api.js';
import { Dialog, Empty, ErrorNotice, Field, Loading } from '../ui.jsx';
import { CATEGORIES, formatQuantity, UNITS, useLoad, useToast } from '../shared.js';

const label = (code) => code.charAt(0).toUpperCase() + code.slice(1);

export function ExpiryBadge({ status, days }) {
  if (status === 'expired') {
    return <span className="badge badge-expired"><WarningCircle size={14} weight="bold" aria-hidden="true" />
      Expired {days === -1 ? 'yesterday' : `${-days} days ago`}</span>;
  }
  if (status === 'expiring_soon') {
    return <span className="badge badge-soon"><Clock size={14} weight="bold" aria-hidden="true" />
      {days === 0 ? 'Expires today' : days === 1 ? 'Expires tomorrow' : `Expires in ${days} days`}</span>;
  }
  if (status === 'ok') {
    return <span className="badge badge-ok"><CheckCircle size={14} weight="bold" aria-hidden="true" />
      {days} days left</span>;
  }
  return <span className="badge badge-none">No expiry date</span>;
}

export default function PantryPage() {
  const { data: items, error, loading, reload } = useLoad(() => api('/pantry-items'), 'pantry');
  const [editing, setEditing] = useState(null);   // null, 'new' or an item
  const [action, setAction] = useState(null);     // { item, type }
  const toast = useToast();

  return (
    <div>
      <div className="page-head">
        <div>
          <h1>Pantry</h1>
          <p className="muted">What you have, sorted by what expires first.</p>
        </div>
        <button type="button" className="btn btn-primary" onClick={() => setEditing('new')}>
          <Plus size={18} weight="bold" aria-hidden="true" /> Add item
        </button>
      </div>
      <ErrorNotice error={error} />
      {loading && !items && <Loading />}
      {items && items.length === 0 && (
        <Empty title="Your pantry is empty">
          <p>Add what you have at home to get expiry reminders and recipe ideas.</p>
        </Empty>
      )}
      {items && items.length > 0 && (
        <ul className="rows" aria-label="Pantry items">
          {items.map((item) => (
            <li key={item.id} className="row">
              <div className="stack" style={{ gap: 4 }}>
                <h2 style={{ fontSize: '1rem' }}>{item.name}</h2>
                <div className="row-meta">
                  <span className="num">{formatQuantity(item.quantityRemaining)} {item.unit} left</span>
                  <span>{label(item.category)}</span>
                  <ExpiryBadge status={item.expiryStatus} days={item.daysUntilExpiry} />
                </div>
              </div>
              <div className="row-actions">
                <button type="button" className="btn btn-small" disabled={Number(item.quantityRemaining) === 0}
                  onClick={() => setAction({ item, type: 'used' })}>Use</button>
                <button type="button" className="btn btn-small" disabled={Number(item.quantityRemaining) === 0}
                  onClick={() => setAction({ item, type: 'finished' })}>Finished</button>
                <button type="button" className="btn btn-small" disabled={Number(item.quantityRemaining) === 0}
                  onClick={() => setAction({ item, type: 'discarded' })}>Throw away</button>
                <button type="button" className="btn btn-small" onClick={() => setEditing(item)}>
                  Edit<span className="visually-hidden"> {item.name}</span>
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}
      {editing && (
        <ItemDialog item={editing === 'new' ? null : editing} onClose={() => setEditing(null)}
          onSaved={(message) => { setEditing(null); toast(message); reload(); }} />
      )}
      {action && (
        <EventDialog {...action} onClose={() => setAction(null)}
          onSaved={(message) => { setAction(null); toast(message); reload(); }} />
      )}
    </div>
  );
}

const EVENT_COPY = {
  used: { title: 'Use some', button: 'Record use', done: 'Use recorded.' },
  finished: { title: 'Mark as finished', button: 'Mark finished', done: 'Marked as finished.' },
  discarded: { title: 'Throw away', button: 'Record as thrown away', done: 'Recorded as thrown away.' },
};

function EventDialog({ item, type, onClose, onSaved }) {
  const copy = EVENT_COPY[type];
  const [quantity, setQuantity] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [key] = useState(newRequestKey);   // reused if the same submit is retried

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api(`/pantry-items/${item.id}/events`, {
        method: 'POST',
        headers: { 'Idempotency-Key': key },
        body: type === 'finished' ? { type } : { type, quantity: Number(quantity) },
      });
      onSaved(copy.done);
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  }

  return (
    <Dialog open title={`${copy.title}: ${item.name}`} onClose={onClose}>
      <form className="stack" onSubmit={submit} noValidate>
        <p className="muted">
          {formatQuantity(item.quantityRemaining)} {item.unit} left.
          {type === 'finished' && ' All of it will be recorded as used.'}
        </p>
        {type !== 'finished' && (
          <Field label={`How much (${item.unit})`} type="number" inputMode="decimal" min="0" step="any" required
            value={quantity} onChange={(e) => setQuantity(e.target.value)}
            error={error?.fieldErrors.quantity} />
        )}
        <ErrorNotice error={error && !error.fieldErrors.quantity ? error : null} />
        <div className="dialog-actions">
          <button type="button" className="btn" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>{copy.button}</button>
        </div>
      </form>
    </Dialog>
  );
}

function ItemDialog({ item, onClose, onSaved }) {
  const [form, setForm] = useState({
    name: item?.name ?? '',
    category: item?.category ?? 'other',
    quantity: item?.quantity ?? '',
    unit: item?.unit ?? 'piece',
    purchasePrice: item?.purchasePrice ?? '',
    purchaseDate: item?.purchaseDate ?? '',
    expiryDate: item?.expiryDate ?? '',
  });
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const { data: risk } = useLoad(() => (item ? api(`/pantry-items/${item.id}/waste-risk`) : Promise.resolve(null)),
    `risk:${item?.id}`);
  const set = (name) => (e) => setForm((f) => ({ ...f, [name]: e.target.value }));
  const fieldError = (name) => error?.fieldErrors[name];

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    const body = {
      ...form,
      quantity: form.quantity === '' ? null : Number(form.quantity),
      purchasePrice: form.purchasePrice === '' ? null : Number(form.purchasePrice),
      purchaseDate: form.purchaseDate || null,
      expiryDate: form.expiryDate || null,
      ...(item ? { version: item.version } : {}),
    };
    try {
      await api(item ? `/pantry-items/${item.id}` : '/pantry-items', { method: item ? 'PATCH' : 'POST', body });
      onSaved(item ? 'Item updated.' : 'Item added.');
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  }

  async function remove() {
    setBusy(true);
    try {
      await api(`/pantry-items/${item.id}`, { method: 'DELETE' });
      onSaved('Item removed from your pantry.');
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  }

  const general = error && !Object.keys(error.fieldErrors).length ? error : null;
  return (
    <Dialog open title={item ? `Edit ${item.name}` : 'Add item'} onClose={onClose}>
      <form className="stack" onSubmit={submit} noValidate>
        <Field label="Name" value={form.name} onChange={set('name')} required maxLength={100}
          error={fieldError('name')} />
        <div className="grid-2">
          <Field label="Quantity" type="number" inputMode="decimal" min="0" step="any" value={form.quantity}
            onChange={set('quantity')} required error={fieldError('quantity')}
            hint={item ? 'Correct the total you bought. What you already used stays recorded.' : undefined} />
          <Field label="Unit" error={fieldError('unit')}>
            {(props) => (
              <select {...props} value={form.unit} onChange={set('unit')}>
                {UNITS.map((u) => <option key={u} value={u}>{u}</option>)}
              </select>
            )}
          </Field>
        </div>
        <Field label="Category" error={fieldError('category')}>
          {(props) => (
            <select {...props} value={form.category} onChange={set('category')}>
              {CATEGORIES.map((c) => <option key={c} value={c}>{label(c)}</option>)}
            </select>
          )}
        </Field>
        <div className="grid-2">
          <Field label="Purchase date (optional)" type="date" value={form.purchaseDate}
            onChange={set('purchaseDate')} error={fieldError('purchaseDate')} />
          <Field label="Expiry date (optional)" type="date" value={form.expiryDate}
            onChange={set('expiryDate')} error={fieldError('expiryDate')} />
        </div>
        <Field label="Price paid (optional)" type="number" inputMode="decimal" min="0" step="0.01"
          value={form.purchasePrice} onChange={set('purchasePrice')} error={fieldError('purchasePrice')}
          hint="Total paid for this quantity. Used to estimate money saved." />
        {risk?.status === 'insufficient_data' && (
          <p className="small muted">Waste-risk estimate: not available yet. {risk.reason}</p>
        )}
        <ErrorNotice error={general} />
        <div className="dialog-actions">
          {item && !confirmDelete && (
            <button type="button" className="btn btn-danger" onClick={() => setConfirmDelete(true)}>Remove</button>
          )}
          {item && confirmDelete && (
            <button type="button" className="btn btn-danger" onClick={remove} disabled={busy}>
              Confirm remove
            </button>
          )}
          <span style={{ flex: 1 }} />
          <button type="button" className="btn" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>{item ? 'Save' : 'Add item'}</button>
        </div>
        {confirmDelete && (
          <p className="small muted">Removing takes it out of your pantry. It is not counted as used or thrown away.</p>
        )}
      </form>
    </Dialog>
  );
}
