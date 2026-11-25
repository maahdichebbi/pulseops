import { useState, type FormEvent } from 'react';
import { ApiRequestError, authApi, setToken } from '../api/client';

interface AuthFormProps {
  onAuthenticated: () => void;
}

export function AuthForm({ onAuthenticated }: AuthFormProps) {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<string[]>([]);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setFieldErrors([]);
    setSubmitting(true);

    try {
      if (mode === 'register') {
        await authApi.register(email, password);
      }
      const { token } = await authApi.login(email, password);
      setToken(token);
      onAuthenticated();
    } catch (err) {
      if (err instanceof ApiRequestError) {
        setError(err.message);
        setFieldErrors(err.fieldErrors.map((f) => `${f.field}: ${f.message}`));
      } else {
        setError('Unable to reach server');
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="auth-shell">
      <div className="card auth-card">
        <div className="brand">
          <span className="dot dot-up" />
          <h1>PulseOps</h1>
        </div>
        <p className="muted">HTTP Endpoint Monitoring</p>

        <div className="tabs">
          <button
            type="button"
            className={mode === 'login' ? 'tab active' : 'tab'}
            onClick={() => { setMode('login'); setError(null); setFieldErrors([]); }}
          >
            Sign in
          </button>
          <button
            type="button"
            className={mode === 'register' ? 'tab active' : 'tab'}
            onClick={() => { setMode('register'); setError(null); setFieldErrors([]); }}
          >
            Create account
          </button>
        </div>

        <form onSubmit={handleSubmit} className="stack">
          <label className="field">
            <span>Email</span>
            <input
              type="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@example.com"
            />
          </label>

          <label className="field">
            <span>Password</span>
            <input
              type="password"
              required
              minLength={8}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="Minimum 8 characters"
            />
          </label>

          {error && (
            <div className="alert-error">
              <div>{error}</div>
              {fieldErrors.map((fe) => (
                <div key={fe} className="field-err">{fe}</div>
              ))}
            </div>
          )}

          <button type="submit" className="btn-primary" disabled={submitting}>
            {submitting
              ? 'Please wait...'
              : mode === 'login'
              ? 'Sign in'
              : 'Create account & sign in'}
          </button>
        </form>
      </div>
    </div>
  );
}
