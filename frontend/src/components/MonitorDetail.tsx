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
  const [hoverIdx, setHoverIdx] = useState<number | null>(null);

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

  const width = 440;
  const height = 104;
  const padX = 8;
  const padTop = 10;
  const padBottom = 10;

  const values = points.map((p) => p.latencyMs);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const avg = Math.round(values.reduce((a, b) => a + b, 0) / values.length);
  const range = Math.max(max - min, 1);

  const coords = points.map((p, idx) => {
    const x = padX + (idx / (points.length - 1)) * (width - padX * 2);
    const y =
      height -
      padBottom -
      ((p.latencyMs - min) / range) * (height - padTop - padBottom);
    return { x, y, point: p };
  });

  const avgY =
    height -
    padBottom -
    ((avg - min) / range) * (height - padTop - padBottom);

  const linePoints = coords
    .map((c) => `${c.x.toFixed(1)},${c.y.toFixed(1)}`)
    .join(' ');
  const areaPoints = `${padX},${height - padBottom} ${linePoints} ${width - padX},${height - padBottom}`;

  const active = hoverIdx != null ? coords[hoverIdx] : null;

  return (
    <div className="stack" style={{ gap: '0.5rem' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
        <h3 style={{ margin: 0 }}>Latency history</h3>
        <span className="mono" style={{ fontSize: '0.78rem' }}>
          {active ? (
            <>
              <strong>{active.point.latencyMs} ms</strong>{' '}
              <span className="muted">
                · {new Date(active.point.checkedAt).toLocaleTimeString()}
              </span>
            </>
          ) : (
            <span className="muted">avg {avg} ms</span>
          )}
        </span>
      </div>

      <svg
        viewBox={`0 0 ${width} ${height}`}
        className="sparkline-svg"
        style={{ height: '104px' }}
        onMouseLeave={() => setHoverIdx(null)}
      >
        <defs>
          <linearGradient id="latFill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#eef1f6" stopOpacity="0.16" />
            <stop offset="100%" stopColor="#eef1f6" stopOpacity="0.0" />
          </linearGradient>
        </defs>

        {/* Top / bottom bounds and average baseline */}
        <line x1={padX} y1={padTop} x2={width - padX} y2={padTop} stroke="#1a202e" strokeWidth="0.7" />
        <line
          x1={padX}
          y1={avgY}
          x2={width - padX}
          y2={avgY}
          stroke="#2a3347"
          strokeWidth="0.8"
          strokeDasharray="3 3"
        />
        <line x1={padX} y1={height - padBottom} x2={width - padX} y2={height - padBottom} stroke="#1a202e" strokeWidth="0.7" />

        <polygon points={areaPoints} fill="url(#latFill)" />
        <polyline
          fill="none"
          stroke="#cbd5e1"
          strokeWidth="1.6"
          strokeLinecap="round"
          strokeLinejoin="round"
          points={linePoints}
        />

        {/* Crosshair + highlighted point on hover */}
        {active && (
          <g>
            <line
              x1={active.x}
              y1={padTop}
              x2={active.x}
              y2={height - padBottom}
              stroke="#3b465e"
              strokeWidth="1"
              strokeDasharray="2 2"
            />
            <circle
              cx={active.x}
              cy={active.y}
              r="3.8"
              fill={active.point.success ? '#22c55e' : '#ef4444'}
              stroke="#090b0f"
              strokeWidth="1.5"
            />
          </g>
        )}

        {/* Invisible wide hit columns for smooth hover */}
        {coords.map((c, idx) => {
          const colW = (width - padX * 2) / coords.length;
          return (
            <rect
              key={c.point.id}
              x={c.x - colW / 2}
              y={0}
              width={colW}
              height={height}
              fill="transparent"
              style={{ cursor: 'crosshair' }}
              onMouseEnter={() => setHoverIdx(idx)}
            />
          );
        })}
      </svg>

      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.75rem' }} className="muted mono">
        <span>min {min} ms</span>
        <span>{points.length} samples</span>
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
  const [hoveredCheck, setHoveredCheck] = useState<CheckResult | null>(null);

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
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
                <h3 style={{ margin: 0 }}>Check timeline ({bars.length})</h3>
                <span className="mono" style={{ fontSize: '0.76rem' }}>
                  {hoveredCheck ? (
                    <>
                      <span style={{ color: hoveredCheck.success ? 'var(--up)' : 'var(--down)' }}>
                        {hoveredCheck.success ? 'UP' : 'DOWN'}
                      </span>{' '}
                      <span className="muted">
                        {hoveredCheck.statusCode ?? '—'} · {hoveredCheck.latencyMs ?? 0} ms ·{' '}
                        {new Date(hoveredCheck.checkedAt).toLocaleTimeString()}
                      </span>
                    </>
                  ) : (
                    <span className="muted">Last {bars.length} checks</span>
                  )}
                </span>
              </div>

              <div
                className="timeline-strip tall"
                onMouseLeave={() => setHoveredCheck(null)}
              >
                {Array.from({ length: Math.max(0, 30 - bars.length) }).map((_, idx) => (
                  <div key={`empty-${idx}`} className="tl-cell tl-empty" />
                ))}
                {bars.map((r) => (
                  <div
                    key={r.id}
                    className={`tl-cell ${r.success ? 'tl-up' : 'tl-down'} ${
                      hoveredCheck && hoveredCheck.id !== r.id ? 'tl-dim' : ''
                    }`}
                    onMouseEnter={() => setHoveredCheck(r)}
                  />
                ))}
              </div>

              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.75rem' }} className="muted mono">
                <span>{bars.length > 0 ? new Date(bars[0].checkedAt).toLocaleTimeString() : '—'}</span>
                <span>{bars.length > 0 ? new Date(bars[bars.length - 1].checkedAt).toLocaleTimeString() : '—'}</span>
              </div>
            </div>

            <div className="viz-box">
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
