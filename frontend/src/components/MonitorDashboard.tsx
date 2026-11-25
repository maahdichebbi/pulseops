import { useState, type FormEvent } from 'react';
import { ApiRequestError, monitorsApi } from '../api/client';
import type { Monitor } from '../api/types';

interface MonitorDashboardProps {
  monitors: Monitor[];
  selectedId: number | null;
  onSelect: (id: number) => void;
  onRefresh: () => Promise<void>;
}

export function MonitorDashboard({
  monitors,
  selectedId,
  onSelect,
  onRefresh,
}: MonitorDashboardProps) {
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [intervalMinutes, setIntervalMinutes] = useState(5);
  const [timeoutSeconds, setTimeoutSeconds] = useState(10);
  const [failureThreshold, setFailureThreshold] = useState(3);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<string[]>([]);
  const [busyId, setBusyId] = useState<number | null>(null);

  async function handleCreate(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setFieldErrors([]);
    setSubmitting(true);

    try {
      await monitorsApi.create({
        name,
        url,
        intervalMinutes,
        timeoutSeconds,
        failureThreshold,
      });
      setName('');
      setUrl('');
      await onRefresh();
    } catch (err) {
      if (err instanceof ApiRequestError) {
        setError(err.message);
        setFieldErrors(err.fieldErrors.map((f) => `${f.field}: ${f.message}`));
      } else {
        setError('Failed to create monitor');
      }
    } finally {
      setSubmitting(false);
    }
  }

  async function handleToggle(m: Monitor) {
    setBusyId(m.id);
    try {
      if (m.enabled) {
        await monitorsApi.pause(m.id);
      } else {
        await monitorsApi.resume(m.id);
      }
      await onRefresh();
    } finally {
      setBusyId(null);
    }
  }

  async function handleDelete(id: number) {
    setBusyId(id);
    try {
      await monitorsApi.remove(id);
      await onRefresh();
    } finally {
      setBusyId(null);
    }
  }

  function statusBadge(m: Monitor) {
    if (!m.enabled) return <span className="badge badge-paused">PAUSED</span>;
    if (m.lastStatus === 'UP') return <span className="badge badge-up">UP</span>;
    if (m.lastStatus === 'DOWN') return <span className="badge badge-down">DOWN</span>;
    return <span className="badge badge-pending">PENDING</span>;
  }

  return (
    <div className="stack-lg">
      <section className="card">
        <h2>Add Monitor</h2>
        <form onSubmit={handleCreate} className="create-grid">
          <label className="field">
            <span>Name</span>
            <input
              type="text"
              required
              maxLength={100}
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Prod API"
            />
          </label>

          <label className="field field-wide">
            <span>URL</span>
            <input
              type="url"
              required
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              placeholder="https://api.example.com/health"
            />
          </label>

          <label className="field">
            <span>Interval (min)</span>
            <input
              type="number"
              min={1}
              max={60}
              value={intervalMinutes}
              onChange={(e) => setIntervalMinutes(Number(e.target.value))}
            />
          </label>

          <label className="field">
            <span>Timeout (sec)</span>
            <input
              type="number"
              min={1}
              max={30}
              value={timeoutSeconds}
              onChange={(e) => setTimeoutSeconds(Number(e.target.value))}
            />
          </label>

          <label className="field">
            <span>Failure Threshold</span>
            <input
              type="number"
              min={1}
              max={10}
              value={failureThreshold}
              onChange={(e) => setFailureThreshold(Number(e.target.value))}
            />
          </label>

          <div className="create-submit">
            <button type="submit" className="btn-primary" disabled={submitting}>
              {submitting ? 'Adding...' : 'Add Monitor'}
            </button>
          </div>
        </form>

        {error && (
          <div className="alert-error" style={{ marginTop: '0.75rem' }}>
            <div>{error}</div>
            {fieldErrors.map((fe) => (
              <div key={fe} className="field-err">{fe}</div>
            ))}
          </div>
        )}
      </section>

      <section className="card">
        <div className="section-header">
          <h2>Monitors ({monitors.length}/20)</h2>
          <button type="button" className="btn-ghost" onClick={onRefresh}>
            Refresh
          </button>
        </div>

        {monitors.length === 0 ? (
          <p className="muted">No monitors yet. Add one above to start checking.</p>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Status</th>
                  <th>Name</th>
                  <th>URL</th>
                  <th>Interval</th>
                  <th>Latency</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {monitors.map((m) => (
                  <tr
                    key={m.id}
                    className={selectedId === m.id ? 'row-selected' : undefined}
                  >
                    <td>{statusBadge(m)}</td>
                    <td className="fw-600">{m.name}</td>
                    <td className="mono">{m.url}</td>
                    <td>{m.intervalMinutes}m</td>
                    <td>{m.lastResponseMs != null ? `${m.lastResponseMs} ms` : '—'}</td>
                    <td className="actions">
                      <button
                        type="button"
                        className="btn-ghost"
                        onClick={() => onSelect(m.id)}
                      >
                        Details
                      </button>
                      <button
                        type="button"
                        className="btn-ghost"
                        disabled={busyId === m.id}
                        onClick={() => handleToggle(m)}
                      >
                        {m.enabled ? 'Pause' : 'Resume'}
                      </button>
                      <button
                        type="button"
                        className="btn-danger"
                        disabled={busyId === m.id}
                        onClick={() => handleDelete(m.id)}
                      >
                        Delete
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
