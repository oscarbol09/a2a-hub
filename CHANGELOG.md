# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Planned
- Mutual TLS (mTLS) certificate verification for downstream agent connections.
- OpenTelemetry distributed tracing with W3C `traceparent` context exporter.
- Per-agent sliding window rate limiting.

---

## [0.1.0-alpha] - 2026-10-15

### Added
- **Token Bucket Rate Limiting (`RateLimitFilter`):** In-memory non-blocking token bucket filter (120 req/min) protecting sensitive endpoints (`/api/v1/agents`, `/api/v1/tasks`) with RFC 7807 `ProblemDetail` (HTTP 429) and `Retry-After: 60`.
- **A2A Error Contract Conformance:** Downstream responses with `status: "error"` now deterministically transition tasks to `FAILED`, capturing error payloads to `error_detail`.
- **Canonical A2A Body Context:** Injected `contextId` into outgoing downstream request body for strict A2A Protocol v1.0 interoperability.
- **Reactive Health Probe Pipeline:** Chained `.thenAccept` callbacks on virtual thread health probes, guaranteeing immediate asynchronous persistence without loss from batch timeouts.
- **Frontend API Key & WebSocket Auth:** Integrated request interceptor for `X-API-Key` in Axios and STOMP `connectHeaders` credentials in WebSocket client.
- **Encryption Key Environment Hardening:** Prohibited default development encryption key outside explicit `dev`/`test` environments.
- **OpenAPI / Swagger Auth Protection:** Enforced API key requirement for `/v3/api-docs` and `/swagger-ui` in production-like profiles.
- **Production Fail-Fast for Reference Agents:** Enforced startup validation requiring `GEMINI_API_KEY` under `prod`/`production` profiles.
- **Quality Gates:** Added minimum coverage thresholds for JaCoCo (75% LINE) and Vitest (75%).
- **Socket-Level DNS Pinning & TOCTOU Elimination:** Integrated Apache HttpClient 5 with custom `DnsResolver` wired directly to `SsrfValidator`. Pre-connection socket-level IP validation blocks DNS rebinding attacks (CWE-367 / CWE-918) against loopback, private RFC 1918 networks, and cloud metadata.
- **Calibrated Vector Similarity Metrics:** Replaced hardcoded fallback confidence (`0.90`) in `A2AOrchestrator` with real, mathematically bounded cosine similarity `(1.0 - (embedding <=> cast(:queryVector as vector)))` projected from `AgentRepository`.
- **Startup Task Reconciliation:** Added automated recovery for tasks stranded in `SUBMITTED` state across server crashes or process restarts, transitioning them to `FAILED` with diagnostic context.
- **Single-Node Health Probe Guard:** Added atomic reentrancy protection (`AtomicBoolean`) in `AgentHealthMonitor` to prevent overlapping schedule executions.
- **Gemini Frontier Model Standardization:** Standardized Google Gemini model to `gemini-3.8-flash` across backend, reference agents, and Docker Compose configurations, retiring deprecated 2.0 endpoints.

### Changed
- Unified project versioning across backend, agents, and frontend to `0.1.0-alpha`.
- Refactored documentation across `README.md`, `ROADMAP.md`, and `CHANGELOG.md` to align with empirical capabilities and remove speculative marketing terminology.

---

## [0.1.0] - 2026-10-11

### Added
- **RFC 9562 UUIDv7 Primary Keys:** Pure SQL generator `uuid_generate_v7()` using `clock_timestamp()` and `pgcrypto` entropy for monotonic time-ordered clustering, optimizing B-tree insert locality.
- **Optimistic Locking Guardrails:** Hibernate `@Version lock_version` on `agents` and `tasks` tables to prevent lost updates under concurrent updates.
- **SSRF Defense Engine (`SsrfValidator`):** Validates all outbound agent URLs. Resolves hostnames to all associated IP addresses to mitigate multi-homing attacks; blocks RFC 1918 private ranges, carrier-grade NAT (`100.64.0.0/10`), loopback, link-local, cloud metadata (`169.254.169.254`), IPv4-mapped IPv6, and IPv6 ULA (`fc00::/7`).
- **Constant-Time API Key Filter (`ApiKeyAuthFilter`):** Implements path canonicalization via Spring MVC utilities and constant-time string comparison (`MessageDigest.isEqual`) to eliminate CWE-208 timing side channels.
- **Strict HTTP Security Headers:** Configured Content-Security-Policy (segregated for API vs. Swagger UI), `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, HSTS preload (63,072,000s), and Cross-Origin isolation (`COOP`/`CORP: same-origin`).
- **AES-256-GCM Column Encryption (`AesGcmAttributeConverter`):** Transparent entity attribute converter utilizing 96-bit random IVs and 128-bit authentication tags, preventing raw secret exposure in database backups.
- **Virtual Thread Health Monitor (`AgentHealthMonitor`):** Periodic concurrent agent probing executed via Java 21 Virtual Threads (`spring.threads.virtual.enabled=true`). Holds zero database connections during outbound network I/O.
- **Chunked FIFO Health Log Pruning:** Scheduled hourly cleanup executing `WITH doomed AS (SELECT id FROM health_checks WHERE checked_at < :cutoff ORDER BY checked_at ASC LIMIT 5000) DELETE FROM health_checks WHERE id IN (SELECT id FROM doomed)` with 50ms pauses, eliminating table lock contention and WAL checkpoint spikes.
- **RFC 7240 Async Delegation:** `TaskController` processes `Prefer: respond-async` headers, returning `202 Accepted`, a `Location: /api/v1/tasks/{id}` header, and a `Preference-Applied: respond-async` header.
- **Keyset Cursor Pagination for Tasks:** Composite B-tree index on `(agent_id, id DESC)` with covering index `idx_tasks_summary_covering` bypassing PostgreSQL TOAST tables for list queries.
- **Intelligent AI Triage Orchestration (`A2AOrchestrator`):** LangChain4j integration with Google Gemini utilizing temperature 0.0, input length bounding (1,000 characters), and XML delimiters (`<candidate_pool>`, `<user_task>`) for prompt injection defense (OWASP LLM01).
- **Post-Routing Invariant Anti-Hallucination Gate:** Verifies that LLM-selected agent UUIDs exist within the candidate pool, automatically triggering Tier-2 vector similarity fallback with explicit telemetry codes if an invariant is violated.
- **Reference Microservices:** Hardened containerized implementations of `weather-agent` (Spring Boot 3.4 + Spring AI 1.0) and `calculator-agent` (Spring Boot 3.4 + LangChain4j) with domain-specific refusal rules and XML prompt isolation.
- **Automated Seed Bootstrap Discovery (`AgentBootstrapService`):** Declarative registration of seed agents configured via `a2ahub.bootstrap.agents` on application boot with asynchronous reconciliation.

### Changed
- **Transaction Decoupling:** Separated transactional persistence (`AgentHealthPersistenceService`, `TaskPersistenceService`) from non-transactional network orchestrators (`AgentHealthMonitor`, `TaskService`) with `REQUIRES_NEW` propagation to minimize database connection holding times to <2ms.
- **Flexible AgentCard Deserialization:** Lenient parsing in `AgentCard` supporting string, map, and structured object formats for `supportedInterfaces`.
- **First-Level Cache Synchronization:** Added `clearAutomatically = true, flushAutomatically = true` to `@Modifying` queries in `HealthCheckRepository` to ensure consistency between Hibernate session cache and PostgreSQL state.

### Fixed
- **Leftmost Prefix Index Scan Pitfall:** Added standalone index on `health_checks.checked_at` to convert FIFO pruning from sequential scans to logarithmic index range scans.
- **Post-Filter Vector Blindness:** Created partial HNSW index `idx_agents_active_embedding_hnsw` restricted to `status IN ('HEALTHY', 'DEGRADED')` to ensure active candidate availability during cosine distance searches.
- **WebSocket Event Storms:** Suppressed real-time status broadcasts on `/topic/agents` when agent health checks do not reflect a state transition.
- **Database Trigger False Writes:** Replaced unconditional timestamp updates with `IF NEW IS DISTINCT FROM OLD` check in `fn_set_updated_at()`.
- **JPA isNew() Transient Instance Cascade:** Unset boxed default values on `@Version private Long lockVersion` so `isNew()` correctly evaluates `version == null`, directing `save()` to execute `persist()` rather than `merge()`.

### Security
- Resolved SSRF vulnerability vectors against internal networks, container networks, and cloud provider metadata services.
- Hardened Docker runtime images with non-root user execution (`USER 10001:10001` for agents, non-privileged group for backend) and Alpine base OS package upgrades.
