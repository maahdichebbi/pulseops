# PulseOps: Project Context

## What it is
A lightweight monitoring platform for websites and HTTP APIs. Users register
endpoints, the platform checks them periodically, records status code, latency
and timestamps, creates incidents on failure, detects recovery, shows uptime
stats, and sends email notifications.

## Who I am / goal
Final-year Software Engineering student looking for a Cloud/DevOps internship.
This is a portfolio project. I must be able to explain and defend every
decision in an interview. Don't treat it as simple CRUD.

## Stack
- Backend: Java 21, Spring Boot, Spring Security + JWT, Spring Data JPA,
  Maven, PostgreSQL, Flyway, Swagger/OpenAPI
- Frontend: React + TypeScript, MINIMAL (login, monitor list with status,
  history, incidents)
- DevOps: Git/GitHub, Docker, Docker Compose, GitHub Actions, AWS (EC2 first,
  then RDS), Terraform

## Architecture
Modular monolith + a separate monitoring worker (same codebase, worker
profile). Spring scheduling at first. No microservices.
Modules: auth, users, monitors, checks/scheduling, results/history, incidents,
notifications.

## Scope
Core: Phases 1-7 = design, backend, minimal frontend, Docker, CI/CD, AWS
deploy, Terraform.
Stretch ONLY if time remains: Prometheus/Grafana, Postgres job queue,
RabbitMQ, Kubernetes.
Out of scope for MVP: server CPU/RAM monitoring, Redis, microservices.
Deploy a vertical slice early.

## Engineering requirements
- Reliability: timeouts, unreachable endpoints, no overlapping jobs, no
  duplicate incidents, correct recovery detection, configurable failure
  threshold, handle worker crashes
- Security: hashed passwords, proper JWT validation, authorization on every
  endpoint, URL validation, SSRF protection (block localhost, private IPs,
  cloud metadata, validate redirects, handle DNS rebinding), no secrets in Git
- Performance: pagination for history, indexes, avoid needless queries
- Testing: unit + integration tests for success/failure checks, incident
  creation/recovery, auth, concurrency

## How you must work with me
Act as a senior engineer and mentor, not a code generator.
1. Explain design decisions BEFORE implementing.
2. Challenge my bad ideas. Don't just agree.
3. Don't overengineer. No tech just to look impressive.
4. Small incremental changes. Don't rewrite whole modules.
5. Explain important code and concepts so I understand them.
6. Run the tests, or explain how to run them. Never assume code works.
7. Point out security and reliability risks proactively.
8. Don't move to the next phase without my approval.
9. Never put secrets in code or commits.

For each milestone: objective, design, files involved, plan, implement, run
tests, explain, summarize what I learned, propose next step.