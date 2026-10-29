import { Link } from 'react-router';
import { api } from '../api.js';
import { Empty, ErrorNotice, Loading } from '../ui.jsx';
import { formatQuantity, useLoad } from '../shared.js';
import { ExpiryBadge } from './PantryPage.jsx';

export default function AlertsPage() {
  const { data: alerts, error, loading } = useLoad(() => api('/expiry-alerts'), 'alerts');
  return (
    <div>
      <div className="page-head">
        <div>
          <h1>Expiring soon</h1>
          <p className="muted">Food that expires within 3 days, or already has. Most urgent first.</p>
        </div>
        <Link to="/recipes" className="btn">Find recipes</Link>
      </div>
      <ErrorNotice error={error} />
      {loading && !alerts && <Loading />}
      {alerts && alerts.length === 0 && (
        <Empty title="Nothing is about to expire">
          <p>Items appear here 3 days before their expiry date.</p>
        </Empty>
      )}
      {alerts && alerts.length > 0 && (
        <ul className="rows" aria-label="Expiry alerts">
          {alerts.map((alert) => (
            <li key={alert.id} className="row">
              <div className="stack" style={{ gap: 4 }}>
                <h2 style={{ fontSize: '1rem' }}>{alert.item.name}</h2>
                <div className="row-meta">
                  <ExpiryBadge status={alert.type} days={alert.item.daysUntilExpiry} />
                  <span className="num">
                    {formatQuantity(alert.item.quantityRemaining)} {alert.item.unit} left
                  </span>
                  <span>Expiry date {alert.item.expiryDate}</span>
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
