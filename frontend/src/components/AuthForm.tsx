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
        setError('Unable to reach the PulseOps API. Check that the backend is running.');
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="auth-shell">
      <div className="auth-panel">
        <div className="auth-header">
          <div className="brand">
            <span className="dot dot-up" />
            <strong>PulseOps</strong>
          </div>
          <span className="mono muted auth-tag">HTTP MONITORING</span>
        </div>

        <div className="card auth-card">
          <div className="auth-heading">
            <h1>{mode === 'login' ? 'Sign in to console' : 'Create operator account'}</h1>
            <p className="muted">
              {mode === 'login'
                ? 'Enter your credentials to view live endpoint status.'
                : 'Register an account to start monitoring HTTP endpoints.'}
            </p>
          </div>

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
              <span>Email address</span>
              <input
                type="email"
                required
                autoComplete="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="operator@domain.com"
              />
            </label>

            <label className="field">
              <span>Password</span>
              <input
                type="password"
                required
                minLength={8}
                autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="At least 8 characters"
              />
            </label>

            {error && (
              <div className="alert-error">
                <div>{error}</div>
                {fieldErrors.map((fe) => (
                  <div key={fe} className="field-err mono">{fe}</div>
                ))}
              </div>
            )}

            <button type="submit" className="btn-primary auth-submit" disabled={submitting}>
              {submitting
                ? mode === 'login'
                  ? 'Signing in...'
                  : 'Creating account...'
                : mode === 'login'
                ? 'Sign in'
                : 'Create account'}
            </button>
          </form>
        </div>

        <div className="auth-footer mono muted">
          <span>JWT auth · 60m session · Rate-limited</span>
        </div>
      </div>
    </div>
  );
}
