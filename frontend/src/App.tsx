import { useCallback, useEffect, useState } from 'react';
import { authApi, clearToken, getToken, incidentsApi, monitorsApi } from './api/client';
import type { Incident, Monitor, UserProfile } from './api/types';
import { AuthForm } from './components/AuthForm';
import { MonitorDashboard } from './components/MonitorDashboard';
import { MonitorDetail } from './components/MonitorDetail';
import './App.css';

export function App() {
  const [authenticated, setAuthenticated] = useState<boolean>(() => Boolean(getToken()));
  const [user, setUser] = useState<UserProfile | null>(null);
  const [monitors, setMonitors] = useState<Monitor[]>([]);
  const [incidents, setIncidents] = useState<Incident[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);

  const loadData = useCallback(async () => {
    try {
      const [me, monPage, incPage] = await Promise.all([
        authApi.me(),
        monitorsApi.list(0, 50),
        incidentsApi.listAll(0, 15),
      ]);
      setUser(me);
      setMonitors(monPage.content);
      setIncidents(incPage.content);
    } catch {
      if (!getToken()) {
        setAuthenticated(false);
      }
    }
  }, []);

  useEffect(() => {
    if (authenticated) {
      void loadData();
    }
  }, [authenticated, loadData]);

  function handleLogout() {
    clearToken();
    setAuthenticated(false);
    setUser(null);
    setMonitors([]);
    setIncidents([]);
    setSelectedId(null);
  }

  if (!authenticated) {
    return <AuthForm onAuthenticated={() => setAuthenticated(true)} />;
  }

  const selectedMonitor = monitors.find((m) => m.id === selectedId) ?? null;

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand">
          <span className="dot dot-up" />
          <strong>PulseOps</strong>
        </div>
        <div className="topbar-right">
          {user && <span className="muted">{user.email}</span>}
          <button type="button" className="btn-ghost" onClick={handleLogout}>
            Sign out
          </button>
        </div>
      </header>

      <main className="container stack-lg">
        <MonitorDashboard
          monitors={monitors}
          selectedId={selectedId}
          onSelect={setSelectedId}
          onRefresh={loadData}
        />

        {selectedMonitor && (
          <MonitorDetail
            monitor={selectedMonitor}
            onClose={() => setSelectedId(null)}
          />
        )}

        <section className="card">
          <h2>Recent Incidents</h2>
          {incidents.length === 0 ? (
            <p className="muted">All quiet — no incidents recorded.</p>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Monitor ID</th>
                    <th>Status</th>
                    <th>Opened</th>
                    <th>Resolved</th>
                    <th>Error</th>
                  </tr>
                </thead>
                <tbody>
                  {incidents.map((inc) => (
                    <tr key={inc.id}>
                      <td>#{inc.monitorId}</td>
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
        </section>
      </main>
    </div>
  );
}

export default App;
