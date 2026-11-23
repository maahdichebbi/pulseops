# PulseOps MVP Specification

> Status: Decisions locked — awaiting ER diagram & endpoint list  
> Updated: 2026-11-17

---

## 1. MVP Scope

**In scope (Phases 1–7)**

| Area | What ships |
|---|---|
| Auth | Register, login, JWT (60 min, no refresh, no blacklist); basic login rate limiting |
| Monitors | CRUD for HTTP/HTTPS endpoints; enabled/paused flag; per-monitor interval, threshold, timeout |
| Scheduler | DB-driven (`next_check_at` column); bounded thread pool; no overlapping checks per monitor |
| Checks | HTTP GET; follow ≤ 5 redirects (validate each hop); SSRF validation at creation and at every check |
| Results | Store status code, latency, timestamp; paginated history API; 30-day retention, daily purge |
| Incidents | Auto-create after N consecutive failures; auto-resolve on 1 success; no duplicates |
| Notifications | Email on incident open + recovery; retry up to 3× on failure; log all attempts |
| Uptime stats | % uptime over last 24 h / 7 d / 30 d per monitor |
| Frontend | Login, monitor list (status badge, uptime %), history table, incident list |
| Ops | Docker + Compose, GitHub Actions CI, AWS EC2 + RDS, Terraform |

**Out of scope**

Server resource monitoring, multi-channel alerts, public status page, team accounts, TCP/ICMP checks, response-body assertions, SSL expiry, Redis, RabbitMQ, Kubernetes, Prometheus/Grafana.

---

## 2. Business Rules

### 2.1 Up / Down definition

| Outcome | Condition |
|---|---|
| **Up** | Final response status is 2xx or 3xx (after ≤ 5 validated redirects) |
| **Down** | 4xx, 5xx, timeout, network/DNS error, or SSRF-blocked redirect |

Timeout: configurable per monitor, **default 10 s, max 30 s**.

### 2.2 Failure threshold & incident lifecycle

- Threshold: **per monitor**, default **3**, range **1–10**.
- **Incident opens** after N consecutive failures (first failure that crosses the threshold).
- **Incident closes** after **1 consecutive success** — recovery email sent immediately.
- Only one open incident per monitor at a time (enforced at write time).

### 2.3 Check intervals & limits

| Setting | Value |
|---|---|
| Interval range | 1–60 minutes (whole minutes) |
| Default interval | 5 minutes |
| Max monitors per user | 20 |
| Check timeout | 10 s default, 1–30 s configurable |

### 2.4 Scheduling design

- Each monitor row carries `next_check_at` (UTC timestamp).
- A scheduler tick (e.g., every 10 s) selects monitors where `next_check_at ≤ now AND status ≠ RUNNING AND enabled = true`.
- After claiming a monitor, worker sets `status = RUNNING`; on completion sets `next_check_at = now + interval` and clears `RUNNING`.
- Single worker for MVP. The SELECT clause is written so `SKIP LOCKED` can be added later with one line change to support multiple workers without touching application logic.
- Thread pool is bounded (size TBD based on max monitors × min interval; start at 10).

### 2.5 Monitor enabled/paused flag

- Monitors can be **enabled** (default) or **paused**.
- Paused monitors: no checks run, `next_check_at` is not advanced, open incidents are left open.
- Re-enabling resets `next_check_at = now` (checks resume immediately).

### 2.6 Retention

- Results older than **30 days** are deleted by a **daily purge job** (Spring `@Scheduled`, runs at low-traffic time, e.g., 03:00 UTC).
- No row-count cap. Purge is time-based only.

### 2.7 Uptime calculation

Uptime % = (successful checks / total checks) × 100, computed on-the-fly from the `results` table for the requested window (24 h, 7 d, 30 d). Cached at the API layer only if query proves slow.

### 2.8 Notifications

- Transport: Spring Mail over SMTP (Mailpit locally, AWS SES SMTP in production).
- On failure: log the error and retry up to **3 times** with exponential back-off before giving up.
- Triggers: incident opened, incident recovered.

### 2.9 SSRF protection

Reject any URL (at creation AND at every check) that:

- Uses a scheme other than `http` or `https`
- Resolves to any of: loopback (`127.0.0.0/8`, `::1`), private RFC 1918 (`10/8`, `172.16/12`, `192.168/16`), link-local (`169.254.0.0/16`), shared address space (`100.64.0.0/10`), unspecified (`0.0.0.0/8`), AWS metadata (`169.254.169.254`)
- Uses IPv6 ULA (`fc00::/7`), link-local (`fe80::/10`), or IPv4-mapped IPv6 (`::ffff:0:0/96`)
- DNS rebinding: **resolve once, validate the IP, then pass the validated IP directly to the HTTP client** — do not re-resolve at connect time.
- Redirect hops: validate every intermediate URL with the same rules before following.

### 2.10 Auth

- Single JWT, **60-minute expiry**, HS256, secret from environment variable (never in Git).
- No refresh token, no server-side blacklist (stateless). Logout is client-side token discard.
- Login endpoint: rate-limited (e.g., 5 attempts / minute per IP) to mitigate brute-force.

---

## 3. Remaining Open Questions

1. **HTTP method**: GET assumed for all checks. Should users be able to configure POST with a body (e.g., health-check endpoints that require it)? Recommendation: GET only for MVP, POST as stretch.
2. **Uptime query performance**: with 20 users × 20 monitors × ~44 000 rows/month each, the `results` table can grow fast. Confirm that a composite index on `(monitor_id, checked_at)` is sufficient, or flag if you want to discuss a rollup table.
3. **Login rate-limit storage**: in-memory (`Bucket4j`) is fine for single-node MVP; just be aware it resets on restart. Acceptable?
