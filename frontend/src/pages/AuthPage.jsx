import { useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router';
import { useAuth } from '../shared.js';
import { ErrorNotice, Field } from '../ui.jsx';

export default function AuthPage({ mode }) {
  const { user, expired, login, register } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const isRegister = mode === 'register';

  if (user) {
    return <Navigate to={location.state?.from || '/pantry'} replace />;
  }

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await (isRegister ? register(email, password) : login(email, password));
      navigate(location.state?.from || '/pantry', { replace: true });
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  }

  return (
    <div className="auth-page">
      <form className="panel stack auth-card" onSubmit={submit} noValidate>
        <div>
          <p className="brand-mark">PantryChef</p>
          <h1>{isRegister ? 'Create your account' : 'Sign in'}</h1>
          <p className="muted">Cook with what you already have.</p>
        </div>
        {expired && !error && <p className="notice">Your session ended. Sign in again to continue.</p>}
        <ErrorNotice error={error && !Object.keys(error.fieldErrors).length ? error : null} />
        <Field label="Email" type="email" autoComplete="email" value={email} required
          onChange={(e) => setEmail(e.target.value)} error={error?.fieldErrors.email} />
        <Field label="Password" type="password" value={password} required
          autoComplete={isRegister ? 'new-password' : 'current-password'}
          hint={isRegister ? 'At least 8 characters.' : undefined}
          onChange={(e) => setPassword(e.target.value)} error={error?.fieldErrors.password} />
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? 'Please wait' : isRegister ? 'Create account' : 'Sign in'}
        </button>
        <p className="small muted">
          {isRegister ? 'Already have an account? ' : 'New to PantryChef? '}
          <Link to={isRegister ? '/login' : '/register'} state={location.state}>
            {isRegister ? 'Sign in' : 'Create an account'}
          </Link>
        </p>
      </form>
    </div>
  );
}
