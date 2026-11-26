import { useEffect, useMemo, useState } from 'react';
import { geoNaturalEarth1, geoPath } from 'd3-geo';
import { feature } from 'topojson-client';
import type { Topology, GeometryCollection } from 'topojson-specification';
import worldAtlas from 'world-atlas/countries-110m.json';
import { incidentsApi, monitorsApi } from '../api/client';
import type { CheckResult, Incident, Monitor, UptimeResponse } from '../api/types';

interface MonitorDetailProps {
  monitor: Monitor;
  onBack: () => void;
  onRefresh: () => Promise<void>;
}

function LatencySparkline({ results }: { results: CheckResult[] }) {
  const points = [...results]
    .reverse()
    .filter((r): r is CheckResult & { latencyMs: number } => r.latencyMs != null);

  if (points.length < 2) {
    return (
      <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
        Waiting for at least 2 completed checks to plot response times.
      </p>
    );
  }

  const width = 420;
  const height = 74;
  const pad = 6;
  const values = points.map((p) => p.latencyMs);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const range = Math.max(max - min, 1);

  const coords = points.map((p, idx) => {
    const x = pad + (idx / (points.length - 1)) * (width - pad * 2);
    const y = height - pad - ((p.latencyMs - min) / range) * (height - pad * 2);
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  });

  const avg = Math.round(values.reduce((a, b) => a + b, 0) / values.length);

  return (
    <div>
      <svg viewBox={`0 0 ${width} ${height}`} className="sparkline-svg" preserveAspectRatio="none">
        <polyline
          fill="none"
          stroke="#eef1f6"
          strokeWidth="1.8"
          strokeLinecap="round"
          strokeLinejoin="round"
          points={coords.join(' ')}
        />
      </svg>
      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.76rem' }} className="muted mono">
        <span>min {min} ms</span>
        <span>avg {avg} ms</span>
        <span>max {max} ms</span>
      </div>
    </div>
  );
}

/**
 * Real Natural Earth world map plotting the resolved target server coordinates.
 */
function ServerWorldMap({ monitor }: { monitor: Monitor }) {
  const hasCoords = monitor.geoLat != null && monitor.geoLon != null;

  const { countryPaths, pin } = useMemo(() => {
    const width = 640;
    const height = 300;
    const projection = geoNaturalEarth1()
      .scale(116)
      .translate([width / 2, height / 2 + 12]);
    const pathGen = geoPath(projection);

    const topology = worldAtlas as unknown as Topology<{
      countries: GeometryCollection;
    }>;
    const countries = feature(topology, topology.objects.countries);
    const paths = countries.features.map((f, idx) => ({
      id: f.id ?? idx,
      d: pathGen(f) ?? '',
    }));

    const projected = hasCoords
      ? projection([monitor.geoLon!, monitor.geoLat!])
      : null;

    return { countryPaths: paths, pin: projected };
  }, [hasCoords, monitor.geoLat, monitor.geoLon]);

  const pinColor =
    !monitor.enabled
      ? '#8690a2'
      : monitor.lastStatus === 'DOWN'
      ? '#ef4444'
      : monitor.lastStatus === 'UP'
      ? '#22c55e'
      : '#eab308';

  const locationLabel = [monitor.geoCity, monitor.geoCountry].filter(Boolean).join(', ');

  return (
    <div className="viz-box">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h3 style={{ margin: 0 }}>Server location</h3>
        <span className="mono muted" style={{ fontSize: '0.75rem' }}>
          {monitor.targetIp ?? 'Unresolved'}
        </span>
      </div>

      <svg
        viewBox="0 0 640 300"
        style={{
          width: '100%',
          height: '210px',
          background: '#090b0f',
          borderRadius: '6px',
          border: '1px solid #202634',
        }}
      >
        <g>
          {countryPaths.map((c) => (
            <path
              key={c.id}
              d={c.d}
              fill="#151b28"
              stroke="#242d40"
              strokeWidth="0.6"
            />
          ))}
        </g>

        {pin && (
          <g>
            <circle
              cx={pin[0]}
              cy={pin[1]}
              r="5"
              fill="none"
              stroke={pinColor}
              strokeWidth="1.5"
              className="map-pulse-ring"
              style={{ transformOrigin: `${pin[0]}px ${pin[1]}px` }}
            />
            <circle cx={pin[0]} cy={pin[1]} r="10" fill={pinColor} fillOpacity="0.18" />
            <circle cx={pin[0]} cy={pin[1]} r="4.5" fill={pinColor} stroke="#090b0f" strokeWidth="1.5" />
          </g>
        )}
      </svg>

      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.78rem' }}>
        <span>{locationLabel || 'Location pending next check'}</span>
        {hasCoords && (
          <span className="mono muted">
            {monitor.geoLat!.toFixed(2)}, {monitor.geoLon!.toFixed(2)}
          </span>
        )}
      </div>
    </div>
  );
}

export function MonitorDetail({ monitor, onBack, onRefresh }: MonitorDetailProps) {
  const [uptime, setUptime] = useState<UptimeResponse | null>(null);
  const [results, setResults] = useState<CheckResult[]>([]);
  const [incidents, setIncidents] = useState<Incident[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);

  async function loadDetail(showSpinner: boolean) {
    if (showSpinner) setLoading(true);
    try {
      const [u, r, inc] = await Promise.all([
        monitorsApi.uptime(monitor.id),
        monitorsApi.results(monitor.id, 0, 30),
        incidentsApi.listForMonitor(monitor.id, 0, 10),
      ]);
      setUptime(u);
      setResults(r.content);
      setIncidents(inc.content);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadDetail(true);
  }, [monitor.id, monitor.lastCheckedAt]);

  async function handleRefresh() {
    setRefreshing(true);
    try {
      await Promise.all([onRefresh(), loadDetail(false)]);
    } finally {
      setRefreshing(false);
    }
  }

  function formatPct(pct: number | null | undefined) {
    return pct == null ? '—' : `${pct.toFixed(2)}%`;
  }

  const bars = [...results].slice(0, 30).reverse();

  return (
    <div className="stack-lg">
      <div className="detail-topbar">
        <button type="button" className="btn-ghost" onClick={onBack}>
          ← All monitors
        </button>
        <button
          type="button"
          className="btn-ghost"
          disabled={refreshing}
          onClick={handleRefresh}
        >
          {refreshing ? 'Syncing...' : 'Refresh'}
        </button>
      </div>

      <div className="detail-header">
        <div>
          <h2 style={{ margin: 0 }}>{monitor.name}</h2>
          <span className="mono muted" style={{ fontSize: '0.8rem' }}>{monitor.url}</span>
        </div>
        <span className="mono muted" style={{ fontSize: '0.78rem' }}>
          {monitor.intervalMinutes}m interval · {monitor.timeoutSeconds}s timeout · {monitor.failureThreshold}x threshold
        </span>
      </div>

      {loading ? (
        <div className="card">
          <p className="muted" style={{ margin: 0 }}>Loading check history...</p>
        </div>
      ) : (
        <>
          {uptime && (
            <div className="stats-grid">
              <div className="stat-box">
                <span className="muted" style={{ fontSize: '0.78rem' }}>Last 24 hours</span>
                <strong>{formatPct(uptime.last24Hours.uptimePercentage)}</strong>
                <small className="mono muted">
                  {uptime.last24Hours.totalChecks === 0
                    ? 'Waiting for checks'
                    : `${uptime.last24Hours.successfulChecks}/${uptime.last24Hours.totalChecks} passed`}
                </small>
              </div>
              <div className="stat-box">
                <span className="muted" style={{ fontSize: '0.78rem' }}>Last 7 days</span>
                <strong>{formatPct(uptime.last7Days.uptimePercentage)}</strong>
                <small className="mono muted">
                  {uptime.last7Days.totalChecks === 0
                    ? 'Waiting for checks'
                    : `${uptime.last7Days.successfulChecks}/${uptime.last7Days.totalChecks} passed`}
                </small>
              </div>
              <div className="stat-box">
                <span className="muted" style={{ fontSize: '0.78rem' }}>Last 30 days</span>
                <strong>{formatPct(uptime.last30Days.uptimePercentage)}</strong>
                <small className="mono muted">
                  {uptime.last30Days.totalChecks === 0
                    ? 'Waiting for checks'
                    : `${uptime.last30Days.successfulChecks}/${uptime.last30Days.totalChecks} passed`}
                </small>
              </div>
            </div>
          )}

          <div className="viz-grid">
            <div className="viz-box">
              <h3 style={{ margin: 0 }}>Check timeline ({bars.length} recorded)</h3>
              <div className="timeline-strip tall">
                {Array.from({ length: Math.max(0, 30 - bars.length) }).map((_, idx) => (
                  <div key={`empty-${idx}`} className="tl-cell tl-empty" title="No check yet" />
                ))}
                {bars.map((r) => (
                  <div
                    key={r.id}
                    className={`tl-cell ${r.success ? 'tl-up' : 'tl-down'}`}
                    title={`${r.success ? 'UP' : 'DOWN'} · HTTP ${r.statusCode ?? '—'} · ${r.latencyMs ?? 0} ms · ${new Date(r.checkedAt).toLocaleString()}`}
                  />
                ))}
              </div>
              <span className="muted" style={{ fontSize: '0.76rem' }}>
                Oldest on left, latest on right. Hover a bar to inspect that check.
              </span>
            </div>

            <div className="viz-box">
              <h3 style={{ margin: 0 }}>Latency history</h3>
              <LatencySparkline results={results} />
            </div>
          </div>

          <ServerWorldMap monitor={monitor} />

          <section className="card">
            <h3>Check log</h3>
            {results.length === 0 ? (
              <div className="empty-state">
                Waiting for the first check — the scheduler polls every 10 seconds.
              </div>
            ) : (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Timestamp</th>
                      <th>Result</th>
                      <th>HTTP</th>
                      <th>Latency</th>
                      <th>Detail</th>
                    </tr>
                  </thead>
                  <tbody>
                    {results.slice(0, 15).map((r) => (
                      <tr key={r.id}>
                        <td className="mono">{new Date(r.checkedAt).toLocaleString()}</td>
                        <td>
                          <span className={r.success ? 'badge badge-up' : 'badge badge-down'}>
                            {r.success ? 'UP' : 'DOWN'}
                          </span>
                        </td>
                        <td className="mono">{r.statusCode ?? '—'}</td>
                        <td className="mono">{r.latencyMs != null ? `${r.latencyMs} ms` : '—'}</td>
                        <td className="muted">{r.errorDetail ?? '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <section className="card">
            <h3>Incidents for this site</h3>
            {incidents.length === 0 ? (
              <div className="empty-state">
                No downtime recorded — this endpoint has stayed healthy.
              </div>
            ) : (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Status</th>
                      <th>Opened</th>
                      <th>Resolved</th>
                      <th>Cause</th>
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
                        <td className="mono">{new Date(inc.openedAt).toLocaleString()}</td>
                        <td className="mono">
                          {inc.resolvedAt ? new Date(inc.resolvedAt).toLocaleString() : 'Ongoing'}
                        </td>
                        <td className="muted">{inc.lastError ?? '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </>
      )}
    </div>
  );
}
