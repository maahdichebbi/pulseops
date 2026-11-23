# PulseOps

HTTP endpoint monitoring platform built with Java 21, Spring Boot 4.1.2, and PostgreSQL 16.

---

## Prerequisites

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (running)
- Java 21 (only needed if you want to run outside Docker)
- Maven wrapper is bundled — no Maven installation needed

---

## Running the app locally

### 1. Create your `.env` file

```bash
cp .env.example .env
```

Edit `.env` and set a strong `JWT_SECRET` (at least 32 characters). The other defaults work for local dev.

### 2. Start Postgres and the API

```powershell
docker compose up -d
```

This starts:
- **postgres** — PostgreSQL 16 on port 5432
- **mailpit** — local SMTP + web UI at http://localhost:8025
- **api** — Spring Boot app on http://localhost:8080

Flyway automatically creates the database schema on first start.

### 3. Run without Docker (dev mode)

Start only Postgres:
```powershell
docker compose up -d postgres
```

Then run the app directly:
```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

---

## Running the tests

```powershell
.\mvnw.cmd verify
```

This runs:
- Unit tests (no DB, milliseconds)
- Integration tests (Testcontainers spins up a fresh Postgres automatically)

**Docker Desktop must be running** for integration tests (Testcontainers needs Docker).

Test breakdown:
| Test class | What it covers |
|---|---|
| `JwtServiceTest` | JWT generation, expiry, tamper, wrong signature, weak secret guard |
| `RegisterRequestValidationTest` | Bean Validation rules (email format, password length bounds) |
| `AuthIntegrationTest` | Full HTTP flow: register, login, /users/me, all error cases |
| `JwtSecretValidationTest` | App refuses to start with a short or blank secret |
| `PulseOpsApplicationTest` | Spring context loads with Flyway + Hibernate + Security |

---

## Building the Docker image

```powershell
docker build -t pulseops:latest .
```

The image is multi-stage:
1. **Builder** (Maven + JDK 21) — compiles the code and produces the JAR
2. **Runtime** (JRE 21 only) — ~220 MB, no source code, runs as a non-root user

Run it manually:
```powershell
docker run -p 8080:8080 `
  -e DB_HOST=localhost `
  -e DB_PORT=5432 `
  -e DB_NAME=pulseops `
  -e DB_USER=pulseops `
  -e DB_PASSWORD=pulseops `
  -e JWT_SECRET=your-secret-here `
  pulseops:latest
```

---

## Environment variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `JWT_SECRET` | **Yes** | — | HS256 signing key — must be ≥ 32 chars |
| `DB_HOST` | No | `localhost` | Postgres host |
| `DB_PORT` | No | `5432` | Postgres port |
| `DB_NAME` | No | `pulseops` | Database name |
| `DB_USER` | No | `pulseops` | Database user |
| `DB_PASSWORD` | No | `pulseops` | Database password |
| `MAIL_HOST` | No | `localhost` | SMTP host |
| `MAIL_PORT` | No | `1025` | SMTP port |

---

## API endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | No | Create account |
| `POST` | `/api/v1/auth/login` | No | Get JWT token |
| `GET` | `/api/v1/users/me` | Bearer JWT | Own profile |
| `GET` | `/api/v1/health` | No | Liveness check |
