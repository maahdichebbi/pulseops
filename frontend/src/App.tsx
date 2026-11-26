import { useCallback, useEffect, useState } from 'react';
import { authApi, clearToken, getToken, incidentsApi, monitorsApi } from './api/client';
import type { CheckResult, Incident, Monitor, UserProfile } from './api/types';
import { AuthForm } from './components/AuthForm';
import { MonitorDashboard } from './components/MonitorDashboard';
import { MonitorDetail } from './components/MonitorDetail';
import './App.css';

type Tab = 'monitors' | 'incidents';

export function App() {
  const [authenticated, setAuthenticated] = useState<boolean>(() => Boolean(getToken()));
  const [tab, setTab] = useState<Tab>('monitors');
  const [user, setUser] = useState<UserProfile | null>(null);
  const [monitors, setMonitors] = useState<Monitor[]>([]);
  const [recentByMonitor, setRecentByMonitor] = useState<Record<number, CheckResult[]>>({});
  const [incidents, setIncidents] = useState<Incident[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);

  const loadData = useCallback(async () => {
    try {
      const [me, monPage, incPage] = await Promise.all([
        authApi.me(),
        monitorsApi.list(0, 50),
        incidentsApi.listAll(0, 30),
      ]);
      setUser(me);
      setMonitors(monPage.content);
      setIncidents(incPage.content);

      const historyEntries = await Promise.all(
        monPage.content.map(async (m) => {
          const res = await monitorsApi.results(m.id, 0, 20);
          return [m.id, res.content] as const;
        })
      );
      setRecentByMonitor(Object.fromEntries(historyEntries));
    } catch {
      if (!getToken()) {
        setAuthenticated(false);
      }
    }
  }, []);

  useEffect(() => {
    if (!authenticated) return;
    void loadData();
    const timer = window.setInterval(() => {
      void loadData();
    }, 15_000);
    return () => window.clearInterval(timer);
  }, [authenticated, loadData]);

  function handleLogout() {
    clearToken();
    setAuthenticated(false);
    setUser(null);
    setMonitors([]);
    setRecentByMonitor({});
    setIncidents([]);
    setSelectedId(null);
  }

  if (!authenticated) {
    return <AuthForm onAuthenticated={() => setAuthenticated(true)} />;
  }

  const selectedMonitor = monitors.find((m) => m.id === selectedId) ?? null;
  const monitorNameById = new Map(monitors.map((m) => [m.id, m.name]));

  const activeMonitors = monitors.filter((m) => m.enabled);
  const upCount = activeMonitors.filter((m) => m.lastStatus === 'UP').length;
  const downCount = activeMonitors.filter((m) => m.lastStatus === 'DOWN').length;
  const pendingCount = activeMonitors.filter((m) => m.lastStatus == null).length;
  const openIncidents = incidents.filter((i) => i.status === 'OPEN').length;

  const latencies = monitors
    .map((m) => m.lastResponseMs)
    .filter((ms): ms is number => ms != null);
  const avgLatency = latencies.length
    ? Math.round(latencies.reduce((a, b) => a + b, 0) / latencies.length)
    : null;

  const heroState = downCount > 0 ? 'down' : pendingCount > 0 || monitors.length === 0 ? 'degraded' : 'up';
  const heroCount = downCount > 0 ? downCount : activeMonitors.length;
  const heroTitle =
    monitors.length === 0
      ? 'No sites monitored yet'
      : downCount > 0
      ? `${downCount === 1 ? 'site' : 'sites'} down`
      : pendingCount > 0 && upCount === 0
      ? `${pendingCount === 1 ? 'site' : 'sites'} awaiting first check`
      : 'All active sites operational';

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="topbar-left">
          <div className="brand">
            <span className={`dot dot-${heroState}`} />
            <strong>PulseOps</strong>
          </div>

          <nav className="nav-pills">
            <button
              type="button"
              className={`nav-pill ${tab === 'monitors' ? 'active' : ''}`}
              onClick={() => { setTab('monitors'); setSelectedId(null); }}
            >
              Monitors
            </button>
            <button
              type="button"
              className={`nav-pill ${tab === 'incidents' ? 'active' : ''}`}
              onClick={() => { setTab('incidents'); setSelectedId(null); }}
            >
              Incidents
              {openIncidents > 0 && <span className="nav-count">{openIncidents}</span>}
            </button>
          </nav>
        </div>

        <div className="topbar-right">
          {user && <span className="user-pill">{user.email}</span>}
          <button type="button" className="btn-ghost" onClick={handleLogout}>
            Sign out
          </button>
        </div>
      </header>

      <main className="container stack-lg">
        {tab === 'monitors' && selectedMonitor ? (
          <MonitorDetail
            monitor={selectedMonitor}
            onBack={() => setSelectedId(null)}
            onRefresh={loadData}
          />
        ) : tab === 'monitors' ? (
          <>
            <section className={`status-hero status-hero-${heroState}`}>
              <div>
                <div className="hero-main">
                  <span className={`hero-count hero-count-${heroState}`}>{heroCount}</span>
                  <span className="hero-label">{heroTitle}</span>
                </div>
                <div className="hero-sub">
                  Polling every 10s · Dashboard syncs every 15s
                </div>
              </div>

              <div className="hero-metrics">
                <div className="hero-metric">
                  <span>Operational</span>
                  <strong>{upCount}/{activeMonitors.length}</strong>
                </div>
                <div className="hero-metric">
                  <span>Open incidents</span>
                  <strong>{openIncidents}</strong>
                </div>
                <div className="hero-metric">
                  <span>Avg latency</span>
                  <strong>{avgLatency != null ? `${avgLatency} ms` : '—'}</strong>
                </div>
              </div>
            </section>

            <MonitorDashboard
              monitors={monitors}
              recentByMonitor={recentByMonitor}
              onSelect={setSelectedId}
              onRefresh={loadData}
            />
          </>
        ) : (
          <section className="card">
            <div className="section-header">
              <h2>Incidents <span className="mono muted">({incidents.length})</span></h2>
              <button type="button" className="btn-ghost" onClick={loadData}>
                Refresh
              </button>
            </div>
            {incidents.length === 0 ? (
              <div className="empty-state">
                No downtime recorded — all endpoints have stayed healthy.
              </div>
            ) : (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Site</th>
                      <th>Status</th>
                      <th>Opened</th>
                      <th>Resolved</th>
                      <th>Cause</th>
                    </tr>
                  </thead>
                  <tbody>
                    {incidents.map((inc) => (
                      <tr key={inc.id}>
                        <td className="fw-600">
                          {monitorNameById.get(inc.monitorId) ?? `Monitor #${inc.monitorId}`}
                        </td>
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
        )}
      </main>
    </div>
  );
}

export default App;
