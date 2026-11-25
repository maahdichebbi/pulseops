import { useEffect, useState } from 'react';
import { incidentsApi, monitorsApi } from '../api/client';
import type { CheckResult, Incident, Monitor, UptimeResponse } from '../api/types';

interface MonitorDetailProps {
  monitor: Monitor;
  onClose: () => void;
}

export function MonitorDetail({ monitor, onClose }: MonitorDetailProps) {
  const [uptime, setUptime] = useState<UptimeResponse | null>(null);
  const [results, setResults] = useState<CheckResult[]>([]);
  const [incidents, setIncidents] = useState<Incident[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);

    Promise.all([
      monitorsApi.uptime(monitor.id),
      monitorsApi.results(monitor.id, 0, 15),
      incidentsApi.listForMonitor(monitor.id, 0, 10),
    ])
      .then(([u, r, inc]) => {
        if (cancelled) return;
        setUptime(u);
        setResults(r.content);
        setIncidents(inc.content);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [monitor.id, monitor.lastCheckedAt]);

  function formatPct(pct: number | null | undefined) {
    return pct == null ? 'No data' : `${pct.toFixed(2)}%`;
  }

  return (
    <section className="card stack-lg">
      <div className="section-header">
        <div>
          <h2>{monitor.name} — Details</h2>
          <p className="mono muted">{monitor.url}</p>
        </div>
        <button type="button" className="btn-ghost" onClick={onClose}>
          Close
        </button>
      </div>

      {loading ? (
        <p className="muted">Loading stats...</p>
      ) : (
        <>
          {uptime && (
            <div className="stats-grid">
              <div className="stat-box">
                <span className="muted">24h Uptime</span>
                <strong>{formatPct(uptime.last24Hours.uptimePercentage)}</strong>
                <small className="muted">
                  {uptime.last24Hours.successfulChecks}/{uptime.last24Hours.totalChecks} checks
                </small>
              </div>
              <div className="stat-box">
                <span className="muted">7d Uptime</span>
                <strong>{formatPct(uptime.last7Days.uptimePercentage)}</strong>
                <small className="muted">
                  {uptime.last7Days.successfulChecks}/{uptime.last7Days.totalChecks} checks
                </small>
              </div>
              <div className="stat-box">
                <span className="muted">30d Uptime</span>
                <strong>{formatPct(uptime.last30Days.uptimePercentage)}</strong>
                <small className="muted">
                  {uptime.last30Days.successfulChecks}/{uptime.last30Days.totalChecks} checks
                </small>
              </div>
            </div>
          )}

          <div>
            <h3>Recent Checks</h3>
            {results.length === 0 ? (
              <p className="muted">No checks recorded yet.</p>
            ) : (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Time</th>
                      <th>Result</th>
                      <th>Status</th>
                      <th>Latency</th>
                      <th>Error</th>
                    </tr>
                  </thead>
                  <tbody>
                    {results.map((r) => (
                      <tr key={r.id}>
                        <td>{new Date(r.checkedAt).toLocaleString()}</td>
                        <td>
                          <span className={r.success ? 'badge badge-up' : 'badge badge-down'}>
                            {r.success ? 'UP' : 'DOWN'}
                          </span>
                        </td>
                        <td>{r.statusCode ?? '—'}</td>
                        <td>{r.latencyMs != null ? `${r.latencyMs} ms` : '—'}</td>
                        <td className="muted">{r.errorDetail ?? '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          <div>
            <h3>Incidents</h3>
            {incidents.length === 0 ? (
              <p className="muted">No incidents for this monitor.</p>
            ) : (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Status</th>
                      <th>Opened</th>
                      <th>Resolved</th>
                      <th>Error</th>
                    </tr>
                  </thead>
                  <tbody>
                    {incidents.map((inc) => (
                      <tr key={inc.id}>
                        <td>
                          <span
                            className={
                              inc.status === 'OPEN' ? 'badge badge-down' : 'badge badge-up'
                            }
                          >
                            {inc.status}
                          </span>
                        </td>
                        <td>{new Date(inc.openedAt).toLocaleString()}</td>
                        <td>
                          {inc.resolvedAt ? new Date(inc.resolvedAt).toLocaleString() : 'Ongoing'}
                        </td>
                        <td className="muted">{inc.lastError ?? '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>
      )}
    </section>
  );
}
