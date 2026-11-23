# PulseOps — API Design

> Status: Decisions locked — awaiting backend skeleton | Updated: 2026-11-17

---

## 1. Endpoint Table

All paths are prefixed `/api/v1`.

### Auth

| Method | Path | Auth | Purpose | Status codes |
|---|---|---|---|---|
| `POST` | `/auth/register` | No | Register new user | 201, 400, 409 |
| `POST` | `/auth/login` | No | Issue JWT; rate-limited (5/min/IP) | 200, 400, 401, 429 |

### Current User

| Method | Path | Auth | Purpose | Status codes |
|---|---|---|---|---|
| `GET` | `/users/me` | Yes | Return own profile (email, created_at) | 200, 401 |

### Monitors

| Method | Path | Auth | Purpose | Status codes |
|---|---|---|---|---|
| `GET` | `/monitors` | Yes | List own monitors with last_status, last_checked_at, last_response_ms, uptime (paginated) | 200, 401 |
| `POST` | `/monitors` | Yes | Create monitor (SSRF-validated) | 201, 400, 401, 422 |
| `GET` | `/monitors/{id}` | Yes | Get monitor detail | 200, 401, 404 |
| `PUT` | `/monitors/{id}` | Yes | Replace monitor config (all fields) | 200, 400, 401, 404, 422 |
| `DELETE` | `/monitors/{id}` | Yes | Hard delete monitor + cascade | 204, 401, 404 |
| `POST` | `/monitors/{id}/pause` | Yes | Set enabled = false | 200, 401, 404 |
| `POST` | `/monitors/{id}/resume` | Yes | Set enabled = true; reset next_check_at = now() | 200, 401, 404 |

### Check History

| Method | Path | Auth | Purpose | Status codes |
|---|---|---|---|---|
| `GET` | `/monitors/{id}/results` | Yes | Paginated check history for monitor | 200, 401, 404 |

### Incidents

| Method | Path | Auth | Purpose | Status codes |
|---|---|---|---|---|
| `GET` | `/monitors/{id}/incidents` | Yes | Paginated incident list for a monitor | 200, 401, 404 |
| `GET` | `/monitors/{monitorId}/incidents/{id}` | Yes | Incident detail + notification log | 200, 401, 404 |
| `GET` | `/incidents` | Yes | All incidents for the authenticated user; filter by `?status=open\|resolved` | 200, 401 |

### Uptime Stats

| Method | Path | Auth | Purpose | Status codes |
|---|---|---|---|---|
| `GET` | `/monitors/{id}/uptime` | Yes | Uptime % for 24h, 7d, 30d (computed on demand) | 200, 401, 404 |

### Health

| Method | Path | Auth | Purpose | Status codes |
|---|---|---|---|---|
| `GET` | `/health` | No | Liveness: `{"status":"UP"}` — no DB ping | 200 |

> Spring Boot Actuator's `/actuator/health` can substitute, but its default configuration exposes disk space, DB state, and other internals. If you use Actuator, explicitly whitelist only `health` and lock down the management port in production.

---

## 2. `GET /monitors` — List Response Shape

The list endpoint includes denormalized fields from `monitors` plus uptime computed by one batch query (see DB design §6). No per-monitor sub-query loop.

```json
{
  "content": [
    {
      "id": 1,
      "name": "My API",
      "url": "https://example.com/health",
      "enabled": true,
      "lastStatus": "UP",
      "lastCheckedAt": "2026-11-17T14:55:00Z",
      "lastResponseMs": 142,
      "uptime24h": 99.3,
      "uptime7d": 98.1,
      "uptime30d": 97.5
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 4,
  "totalPages": 1
}
```

`lastStatus` / `lastCheckedAt` / `lastResponseMs` come from the `monitors` row (zero extra queries). Uptime percentages come from the single batch aggregation query joined in by the service.

---

## 3. Pagination Format

Query params: `?page=0&size=20` (zero-based, default size 20, max 100).

Standard envelope matches Spring Data `Page<T>`: `content`, `page`, `size`, `totalElements`, `totalPages`. No custom wrapper needed.

---

## 4. Error Response Format

```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Monitor not found",
  "path": "/api/v1/monitors/99",
  "timestamp": "2026-11-17T14:00:00Z"
}
```

Validation errors (400) add a `fieldErrors` array:
```json
{ "fieldErrors": [{ "field": "url", "message": "must be http or https" }] }
```

Implemented via `@RestControllerAdvice` + `@ExceptionHandler`. One handler per exception type; never leak stack traces to the response body.

---

## 5. Ownership Enforcement

All data access is scoped to `currentUserId` from the JWT. Pattern:

```
monitorRepository.findByIdAndUserId(id, currentUserId)
    .orElseThrow(() -> new ResourceNotFoundException("Monitor not found"));
```

**Returns 404, not 403.** A 403 confirms the resource exists; a 404 reveals nothing. This is the standard for user-scoped APIs (GitHub, Stripe). Cross-user access to an incident via `/monitors/{monitorId}/incidents/{id}` is enforced by first resolving the monitor with ownership check — if the monitor isn't yours, the incident lookup never runs.

---

## 6. Validation Layers

| Validation | Layer | Reason |
|---|---|---|
| Field format, length, ranges (`@NotBlank`, `@Min`, `@Max`) | DTO — `@Valid` + Bean Validation | Fast-fail before any DB or network I/O |
| URL syntax (scheme, well-formed) | DTO | Cheap structural check |
| SSRF / DNS resolution | Service, after DTO validation | Requires DNS I/O; cannot run inside Bean Validation |
| Ownership | Service, after loading entity | Needs authenticated user from security context |
| Business constraints double-enforced | DB `CHECK` constraints | Last line of defence against direct SQL writes or bugs |

> **422 vs 400:** `400` = malformed (unparseable). `422` = valid structure, semantically rejected (SSRF-blocked URL is a valid URL that we refuse). Know the distinction.

---

## 7. Key Assumptions & Decisions

- `PUT /monitors/{id}` is a full replacement (all fields required). `PATCH` is deferred to post-MVP.
- `POST` for pause/resume — these are state transitions, not partial updates. Using `POST` avoids idempotency confusion (`PATCH enabled=false` on an already-paused monitor is ambiguous; `POST /pause` is not).
- `GET /incidents` (user-scoped dashboard) accepts `?status=open|resolved` filter. No other filters in MVP.
- Hard delete for monitors: cascades `check_results` and `incidents` at the DB level (`ON DELETE CASCADE`).
- No admin role in MVP.

---

## 8. Open Questions

1. **`GET /monitors/{id}/uptime` — separate endpoint or always included in `GET /monitors/{id}`?** The batch query already handles it for the list; running it for a single monitor detail is one targeted query. Recommend: include uptime in the detail response too, so the frontend never needs two calls.
2. **`GET /incidents` pagination** — same `page`/`size` params as other endpoints? Confirm, since the dashboard will likely show only open incidents and the total count is naturally small.
