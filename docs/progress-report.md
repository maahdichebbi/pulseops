# PulseOps — Progress Report & Completion Plan

**Student:** Chebb  
**Project:** PulseOps — HTTP Endpoint Monitoring Platform  
**Date:** November 2026  
**Target:** Final-year Software Engineering project (Cloud/DevOps internship focus)

---

## 1. Project Summary

PulseOps is a self-hosted HTTP monitoring platform that lets users register, add HTTP/HTTPS endpoints to watch, and get automatic alerts when services go down. It tracks uptime, response times, and incident history — built to demonstrate full-stack development, database design, background job scheduling, and cloud deployment skills.

---

## 2. Technology Stack

| Layer | Technology |
|---|---|
| **Backend** | Java 21, Spring Boot 4.1.2, Spring Framework 7 |
| **Database** | PostgreSQL 16, Flyway migrations |
| **ORM** | Hibernate ORM 7 (Spring Data JPA) |
| **Auth** | JWT (HS256, 60-min expiry), BCrypt password hashing |
| **Security** | Spring Security (stateless, filter-based) |
| **Email** | Spring Mail over SMTP (Mailpit locally, AWS SES in production) |
| **Frontend** | React (planned) |
| **Infra** | Docker Compose (local), AWS EC2 + RDS (production) |
| **CI/CD** | GitHub Actions |
| **IaC** | Terraform |

---

## 3. Architecture Overview

```
┌────────────┐     HTTP/JSON      ┌──────────────────────────────────┐
│   Browser   │◄────────────────►│         Spring Boot API           │
│  (React)    │                   │                                  │
└────────────┘                   │  ┌──────────┐  ┌──────────────┐  │
                                  │  │   Auth    │  │   Monitors   │  │
                                  │  │ Register  │  │    CRUD      │  │
                                  │  │  Login    │  │  Pause/Resume│  │
                                  │  │  JWT      │  │  SSRF Check  │  │
                                  │  └──────────┘  └──────────────┘  │
                                  │                                  │
                                  │  ┌──────────────────────────────┐│
                                  │  │  Scheduler (DB-driven)       ││
                                  │  │  next_check_at polling       ││
                                  │  │  Bounded thread pool         ││
                                  │  └──────────────────────────────┘│
                                  │            │                     │
                                  │            ▼                     │
                                  │  ┌──────────────┐ ┌───────────┐ │
                                  │  │ Check Results │ │ Incidents │ │
                                  │  │  + Uptime %   │ │ + Emails  │ │
                                  │  └──────────────┘ └───────────┘ │
                                  └──────────┬───────────────────────┘
                                             │
                                             ▼
                                  ┌──────────────────┐
                                  │   PostgreSQL 16   │
                                  │  (5 tables)       │
                                  └──────────────────┘
```

---

## 4. Database Design (5 tables)

| Table | Purpose | Status |
|---|---|---|
| `users` | User accounts (email, bcrypt hash, timestamps) | ✅ Created |
| `monitors` | Endpoints to watch (URL, interval, threshold, status) | Designed |
| `check_results` | Individual check outcomes (status code, latency) | Designed |
| `incidents` | Failure incidents (open/resolved, timestamps) | Designed |
| `notification_log` | Email delivery attempts and results | Designed |

Key design decisions:
- **DB-driven scheduler** using `next_check_at` + `claimed_until` lease columns (no Redis/RabbitMQ needed)
- **SSRF protection** — validates URLs against private IP ranges at creation and every check
- **Partial unique index** on incidents to enforce one open incident per monitor
- **ON DELETE CASCADE** for monitor deletion (hard delete, no soft delete)

---

## 5. What's Been Built (Phases 1–2)

### Phase 1 — Project Skeleton ✅
- Spring Boot 4.1.2 project with Maven wrapper
- Docker Compose: PostgreSQL 16 + Mailpit (local email testing)
- Flyway migration `V1__create_users.sql`
- Application config (base + local profile), environment-variable driven
- Hibernate schema validation (`ddl-auto: validate`)

### Phase 2 — User Auth (Partially Complete) ✅
- **User entity** + JPA repository with `findByEmail`
- **POST /api/v1/auth/register** — validated, BCrypt-hashed, email normalized
  - 201 Created (success)
  - 400 Bad Request (validation errors with field-level detail)
  - 409 Conflict (duplicate email — both query-check and DB constraint fallback)
- **Error handling** — `@RestControllerAdvice` with spec-compliant error format:
  ```json
  {"status": 409, "error": "Conflict", "message": "...", "path": "...", "timestamp": "..."}
  ```
- **Spring Security** config: stateless, CSRF disabled, JWT filter chain
- **JWT service** (HS256, 60-min expiry, secret from env var)
- **POST /api/v1/auth/login** — returns JWT token (compiled, pending verification)
- **GET /api/v1/users/me** — returns authenticated user profile (compiled, pending verification)

### Code Stats
- **20 Java source files** compiling successfully
- **1 Flyway migration** applied
- **0 test failures** (test suite not yet written — planned for Phase 7)

---

## 6. Completion Roadmap

| Phase | Scope | Key Deliverables | Est. Effort |
|---|---|---|---|
| **3** | Monitor CRUD | `monitors` table migration, entity, full REST API (create, read, update, delete, pause, resume), SSRF URL validation, ownership enforcement | 2–3 days |
| **4** | Scheduler + Checks | DB-driven scheduler (`next_check_at` polling), HTTP check executor, redirect following with SSRF validation per hop, `check_results` table + storage | 3–4 days |
| **5** | Incidents + Notifications | Auto-create incident after N failures, auto-resolve on success, email alerts via Spring Mail, retry logic, `incidents` + `notification_log` tables | 2–3 days |
| **6** | Uptime + History APIs | Uptime % calculation (24h/7d/30d), paginated check history, paginated incident list, 30-day data retention purge job | 1–2 days |
| **7** | Hardening | Login rate limiting, input sanitization review, integration tests (Testcontainers), error handling edge cases | 2–3 days |
| **8** | Frontend | React app: login page, monitor dashboard (status badges, uptime %), history table, incident list | 4–5 days |
| **9** | CI/CD + Deployment | GitHub Actions pipeline (build → test → Docker image), Terraform for AWS (EC2 + RDS + SES), production config | 2–3 days |

**Estimated total remaining: ~3–4 weeks** (working part-time alongside classes)

---

## 7. REST API Summary (16 endpoints)

| Method | Path | Auth | Phase |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | No | ✅ Done |
| `POST` | `/api/v1/auth/login` | No | ✅ Built |
| `GET` | `/api/v1/users/me` | Yes | ✅ Built |
| `GET` | `/api/v1/health` | No | Phase 3 |
| `POST` | `/api/v1/monitors` | Yes | Phase 3 |
| `GET` | `/api/v1/monitors` | Yes | Phase 3 |
| `GET` | `/api/v1/monitors/{id}` | Yes | Phase 3 |
| `PUT` | `/api/v1/monitors/{id}` | Yes | Phase 3 |
| `DELETE` | `/api/v1/monitors/{id}` | Yes | Phase 3 |
| `POST` | `/api/v1/monitors/{id}/pause` | Yes | Phase 3 |
| `POST` | `/api/v1/monitors/{id}/resume` | Yes | Phase 3 |
| `GET` | `/api/v1/monitors/{id}/results` | Yes | Phase 6 |
| `GET` | `/api/v1/monitors/{id}/incidents` | Yes | Phase 5 |
| `GET` | `/api/v1/monitors/{monitorId}/incidents/{id}` | Yes | Phase 5 |
| `GET` | `/api/v1/incidents` | Yes | Phase 5 |
| `GET` | `/api/v1/monitors/{id}/uptime` | Yes | Phase 6 |

---

## 8. Key Design Decisions Worth Discussing

1. **Stateless JWT** (no refresh tokens, no server-side blacklist) — simpler for MVP, logout is client-side token discard
2. **DB-driven scheduler** instead of in-memory cron — survives restarts, single source of truth, easy to scale later with `SKIP LOCKED`
3. **SSRF protection** at creation AND every check — prevents DNS rebinding attacks
4. **404 instead of 403** for unauthorized resource access — standard pattern (GitHub, Stripe) to prevent resource enumeration
5. **Spring Boot 4.1.2** (latest, Spring Framework 7, Hibernate 7) — demonstrates comfort with cutting-edge stack
6. **Flyway** for all schema changes — no Hibernate auto-DDL, full audit trail of database evolution

---

## 9. Discussion Points for Tutor

- **Testing strategy**: Planning Testcontainers for integration tests in Phase 7 — is this sufficient scope, or should unit tests come earlier?
- **Frontend scope**: React with basic pages (login, dashboard, history) — any preference on framework or should I add a specific visualization library?
- **Deployment target**: AWS EC2 + RDS via Terraform — are there university credits/resources available?
- **Timeline risk**: Scheduler + incident logic (Phases 4–5) is the most complex part — should I prioritize that over the frontend?
