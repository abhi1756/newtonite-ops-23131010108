import { useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { errorMessage } from '../api/client';

// Seeded demo accounts (see README). All use the password "demo1234".
const DEMO_USERS = [
  ['alice', 'Payments Lead'],
  ['bob', 'Payments Member'],
  ['judy', 'Payments Member'],
  ['carol', 'Platform Lead'],
  ['dave', 'Platform Member, Payments Viewer'],
  ['ivan', 'Platform Viewer'],
  ['admin', 'Global admin'],
];

export function LoginPage() {
  const { token, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const from = (location.state as { from?: string } | null)?.from ?? '/';
  if (token) return <Navigate to={from} replace />;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(email, password);
      navigate(from, { replace: true });
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login">
      <form className="card" onSubmit={submit}>
        <h1>Newtonite Ops</h1>
        <p className="muted">Sign in to coordinate operational work.</p>
        {error && <div className="alert error">{error}</div>}
        <label>
          Email
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoFocus />
        </label>
        <label>
          Password
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
        </label>
        <button className="primary" disabled={busy}>
          {busy ? 'Signing in...' : 'Sign in'}
        </button>
        <div className="demo">
          <p className="muted">Demo accounts (password demo1234):</p>
          {DEMO_USERS.map(([name, role]) => (
            <button
              type="button"
              key={name}
              className="chip"
              onClick={() => {
                setEmail(`${name}@newtonite.test`);
                setPassword('demo1234');
              }}
            >
              {name} <small>{role}</small>
            </button>
          ))}
        </div>
      </form>
    </div>
  );
}
