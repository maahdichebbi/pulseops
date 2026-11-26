import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiRequestError, monitorsApi } from '../api/client';
import type { CheckResult, Monitor } from '../api/types';

interface MonitorDashboardProps {
  monitors: Monitor[];
  recentByMonitor: Record<number, CheckResult[]>;
  onSelect: (id: number) => void;
  onRefresh: () => Promise<void>;
}

export function MonitorDashboard({
  monitors,
  recentByMonitor,
  onSelect,
  onRefresh,
}: MonitorDashboardProps) {
  const [showCreate, setShowCreate] = useState(false);
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [intervalMinutes, setIntervalMinutes] = useState(5);
  const [timeoutSeconds, setTimeoutSeconds] = useState(10);
  const [failureThreshold, setFailureThreshold] = useState(3);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<string[]>([]);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  // Track previous statuses so only a real UP -> DOWN transition triggers the flip pulse
  const prevStatusRef = useRef<Record<number, Monitor['lastStatus']>>({});
  const [flippedDownIds, setFlippedDownIds] = useState<Record<number, boolean>>({});

  useEffect(() => {
    const nextFlipped: Record<number, boolean> = {};
    for (const m of monitors) {
      const prev = prevStatusRef.current[m.id];
      if (prev === 'UP' && m.lastStatus === 'DOWN') {
        nextFlipped[m.id] = true;
      }
      prevStatusRef.current[m.id] = m.lastStatus;
    }
    if (Object.keys(nextFlipped).length > 0) {
      setFlippedDownIds(nextFlipped);
    }
  }, [monitors]);

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
      setShowCreate(false);
      await onRefresh();
    } catch (err) {
      if (err instanceof ApiRequestError) {
        setError(err.message);
        setFieldErrors(err.fieldErrors.map((f) => `${f.field}: ${f.message}`));
      } else {
        setError('Could not reach the PulseOps API to save this monitor.');
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

  async function handleRefreshClick() {
    setRefreshing(true);
    try {
      await onRefresh();
    } finally {
      setRefreshing(false);
    }
  }

  function statusBadge(m: Monitor) {
    if (!m.enabled) {
      return (
        <span className="badge badge-paused">
          <span className="dot dot-paused" />
          PAUSED
        </span>
      );
    }
    if (m.lastStatus === 'UP') {
      return (
        <span className="badge badge-up">
          <span className="dot dot-up" />
          UP
        </span>
      );
    }
    if (m.lastStatus === 'DOWN') {
      return (
        <span className={`badge badge-down ${flippedDownIds[m.id] ? 'badge-flip-down' : ''}`}>
          <span className="dot dot-down" />
          DOWN
        </span>
      );
    }
    return (
      <span className="badge badge-degraded">
        <span className="dot dot-degraded" />
        PENDING
      </span>
    );
  }

  return (
    <>
      {showCreate && (
        <div className="modal-backdrop" onClick={() => setShowCreate(false)}>
          <div className="modal-card stack" onClick={(e) => e.stopPropagation()}>
            <div className="section-header" style={{ marginBottom: 0 }}>
              <h2 style={{ margin: 0 }}>Add site to monitor</h2>
              <button type="button" className="btn-ghost" onClick={() => setShowCreate(false)}>
                Close
              </button>
            </div>

            <form onSubmit={handleCreate} className="stack">
              <label className="field">
                <span>Site name</span>
                <input
                  type="text"
                  required
                  maxLength={100}
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="GitHub API"
                />
              </label>

              <label className="field">
                <span>HTTPS or HTTP URL</span>
                <input
                  type="url"
                  required
                  value={url}
                  onChange={(e) => setUrl(e.target.value)}
                  placeholder="https://api.github.com"
                />
              </label>

              <div className="form-row-3">
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
                  <span>Failure threshold</span>
                  <input
                    type="number"
                    min={1}
                    max={10}
                    value={failureThreshold}
                    onChange={(e) => setFailureThreshold(Number(e.target.value))}
                  />
                </label>
              </div>

              {error && (
                <div className="alert-error">
                  <div>{error}</div>
                  {fieldErrors.map((fe) => (
                    <div key={fe} className="field-err mono">{fe}</div>
                  ))}
                </div>
              )}

              <div className="modal-actions">
                <button type="button" className="btn-ghost" onClick={() => setShowCreate(false)}>
                  Cancel
                </button>
                <button type="submit" className="btn-primary" disabled={submitting}>
                  {submitting ? 'Saving...' : 'Start monitoring'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      <section className="card">
        <div className="section-header">
          <h2>Monitored sites <span className="mono muted">({monitors.length}/20)</span></h2>
          <div className="toolbar">
            <button
              type="button"
              className="btn-ghost"
              disabled={refreshing}
              onClick={handleRefreshClick}
            >
              {refreshing ? 'Syncing...' : 'Refresh'}
            </button>
            <button
              type="button"
              className="btn-primary"
              onClick={() => { setError(null); setFieldErrors([]); setShowCreate(true); }}
            >
              + Add site
            </button>
          </div>
        </div>

        {monitors.length === 0 ? (
          <div className="empty-state">
            Nothing to show yet — add your first site to start monitoring.
          </div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>State</th>
                  <th>Site</th>
                  <th>Recent checks</th>
                  <th>Latency</th>
                  <th>Every</th>
                  <th style={{ textAlign: 'right' }}>Controls</th>
                </tr>
              </thead>
              <tbody>
                {monitors.map((m) => {
                  const history = [...(recentByMonitor[m.id] ?? [])].slice(0, 20).reverse();
                  const emptySlots = Math.max(0, 20 - history.length);

                  return (
                    <tr
                      key={m.id}
                      className="clickable"
                      onClick={() => onSelect(m.id)}
                    >
                      <td>{statusBadge(m)}</td>
                      <td>
                        <div className="fw-600">{m.name}</div>
                        <div className="mono muted" style={{ fontSize: '0.76rem' }}>{m.url}</div>
                      </td>
                      <td>
                        <div className="timeline-strip" title="Last 20 checks (oldest → newest)">
                          {Array.from({ length: emptySlots }).map((_, idx) => (
                            <div key={`e-${idx}`} className="tl-cell tl-empty" />
                          ))}
                          {history.map((r) => (
                            <div
                              key={r.id}
                              className={`tl-cell ${r.success ? 'tl-up' : 'tl-down'}`}
                              title={`${r.success ? 'UP' : 'DOWN'} · HTTP ${r.statusCode ?? '—'} · ${r.latencyMs ?? 0} ms · ${new Date(r.checkedAt).toLocaleTimeString()}`}
                            />
                          ))}
                        </div>
                      </td>
                      <td className="mono">
                        {m.lastResponseMs != null ? `${m.lastResponseMs} ms` : '—'}
                      </td>
                      <td className="mono muted">{m.intervalMinutes}m</td>
                      <td className="actions" onClick={(e) => e.stopPropagation()}>
                        <button
                          type="button"
                          className="btn-ghost"
                          onClick={() => onSelect(m.id)}
                        >
                          Inspect
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
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}
