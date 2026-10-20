# A2A-Hub Engineering Roadmap

This document outlines the architectural roadmap and release milestones for A2A-Hub.
Milestones are prioritized by production reliability, protocol interoperability, and distributed systems invariants.

---

## Completed Milestones

### [v0.1.0-alpha] - Baseline Architecture & Alpha Engine (Current)

- [x] **Phase 1: Database Foundation & Sequential Clustering**
  - Time-ordered RFC 9562 UUIDv7 IDs generated in pure PostgreSQL SQL (`uuid_generate_v7()`) using `clock_timestamp()` and `pgcrypto` entropy, optimizing B-tree insert locality and reducing page splits.
  - Optimistic locking via Hibernate `@Version lock_version` on `agents` and `tasks` to eliminate lost updates under concurrent modifications.
  - Smart audit trigger `fn_set_updated_at()` suppressing false `updated_at` column writes on no-op row updates (`IF NEW IS DISTINCT FROM OLD`).
  - Explicit Foreign Key indexes on `agent_skills(agent_id)`, `health_checks(agent_id)`, and `tasks(agent_id)` to eliminate table-lock cascade penalties on parent deletions.
  - Automated Flyway migrations schema versioning (V1 through V5).

- [x] **Phase 2: AppSec & Socket-Level SSRF Pinning**
  - Integrated Apache HttpClient 5 with custom `DnsResolver` wired directly to `SsrfValidator`, enforcing pre-connection socket-level IP validation and eliminating TOCTOU DNS rebinding risks.
  - Strict blocking of RFC 1918 private IPv4 blocks, carrier-grade NAT (`100.64.0.0/10`), loopback (`127.0.0.0/8`), link-local (`169.254.0.0/16`), cloud metadata endpoints (`169.254.169.254`), IPv4-mapped IPv6 (`::ffff:0:0/96`), and IPv6 ULA (`fc00::/7`).
  - Constant-time API key verification (`MessageDigest.isEqual`) with Spring MVC path canonicalization to defeat CWE-208 side-channel timing attacks.
  - Strict HTTP security headers suite: Content-Security-Policy (segregated for API vs. Swagger UI), HSTS preload (63,072,000s), `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, and Cross-Origin isolation (`COOP`/`CORP: same-origin`).
  - AES-256-GCM JPA Attribute Converter for sensitive agent tokens using 96-bit random IVs and 128-bit authentication tags.

- [x] **Phase 3: Resilient Agent Health Monitoring & Recovery**
  - Non-blocking concurrent HTTP probes executed across Java 21 Virtual Threads (`spring.threads.virtual.enabled=true`).
  - Architectural decoupling of HTTP network I/O from database transactions: zero HikariCP connections leased during outbound network probes.
  - `AgentHealthPersistenceService` isolated in `@Transactional(REQUIRES_NEW)` transactions (<2ms connection holding duration).
  - Single-node reentrancy protection (`AtomicBoolean`) preventing overlapping schedule executions.
  - Startup task reconciliation recovering stranded `SUBMITTED` tasks across node restarts to prevent silent state stalls.
  - Status transition deduplication suppressing WebSocket DOM event storms during probe cycles (broadcasting only on state changes).
  - Chunked CTE FIFO pruning query (`WITH doomed AS ... DELETE ... LIMIT 5000`) executed hourly with 50ms pauses to prevent MVCC bloat and WAL checkpoint spikes.
  - Covering index on `health_checks.checked_at` and partial index on `(latency_ms) WHERE status = 'HEALTHY'` for sub-millisecond telemetry aggregation.

- [x] **Phase 4: Task Proxying & RFC 7240 Async Delegation**
  - Dual execution dispatch: Synchronous (`201 Created`) and Asynchronous (`202 Accepted` with `Location` header and `Preference-Applied: respond-async`).
  - Correlation tracking via MDC `X-Correlation-Id`, `X-Context-Id`, `X-Task-Id`, and W3C `traceparent` propagation to downstream agents.
  - Monotonic Keyset pagination over UUIDv7 `(agent_id, id DESC)` with covering index (`idx_tasks_summary_covering`) bypassing PostgreSQL TOAST tables for list views.
  - Real-time STOMP WebSocket broadcast on `/topic/tasks`.

- [x] **Phase 5: AI Orchestration Layer & Calibrated Discovery**
  - `A2AOrchestrator` integrating LangChain4j and Google Gemini (`gemini-3.8-flash`) with deterministic temperature (0.0).
  - Input bounded to 1,000 characters to prevent LLM04 Denial of Service and buffer exhaustion.
  - OWASP LLM01 prompt injection defense using XML delimiter isolation (`<candidate_pool>`, `<user_task>`).
  - Invariant validation gate enforcing that LLM-selected agent UUIDs exist within the retrieved candidate set.
  - Calibrated fallback to Tier-2 vector similarity with mathematically bounded cosine similarity scores when Gemini quota (HTTP 429), timeouts, or invalid selections occur.

- [x] **Phase 6: Reference Agent Microservices & Seed Discovery**
  - Reference `weather-agent` using Spring Boot 3.4.3 and Spring AI 1.1 (`ChatClient`).
  - Reference `calculator-agent` using Spring Boot 3.4.3 and LangChain4j (`GoogleAiGeminiChatModel`).
  - Flexible deserialization in `AgentCard` supporting string array, map, and structured object protocol bindings.
  - Declarative seed bootstrap discovery service (`a2ahub.bootstrap.agents`) with asynchronous retry reconciliation.
  - Hardened multi-stage Docker builds running as non-root user (`USER 10001:10001`) on Alpine base images.

---

## Short-Term Milestones (v0.2.0 - v0.3.0)

Target: Cryptographic transport security, two-tier behavioural verification, distributed telemetry, and egress throttling.

- [ ] **Two-Tier Agent Health & Behavioural Contract Verification:**
  - **Tier 1 (Availability & Liveness):** High-frequency lightweight probes to `GET /.well-known/agent-card.json` dispatched across Java 21 Virtual Threads (SSRF-hardened, non-blocking, zero DB connection leasing).
  - **Tier 2 (Behavioural Verification):** Periodic synthetic contract probes validating declared agent skills against `POST /tasks` using deterministic canonical test queries (e.g. validating meteorological answers for weather skills, arithmetic logic for calculator skills).
  - **Reputation & Verification State:** Decouple `HEALTHY` (Tier 1 + Tier 2 verified), `DEGRADED` (Tier 1 alive, Tier 2 failed/degraded), and `SKILLS_UNVERIFIED` within the semantic discovery pool (`idx_agent_skills_tags`) and AI orchestration triage.
  - **Automated Ecosystem Baseline:** Integration of standard behavioural test suites (such as Sundy external verification runners) into CI and registry audits.
- [ ] **TLS Hostname Verification & Strict CN/SAN Pinning:**
  - Enforce explicit X.509 certificate Common Name (CN) and Subject Alternative Name (SAN) validation in Apache HttpClient 5 against registered agent hostnames. Eliminates card/endpoint decoupling where a valid public card points to an unverified or rogue backend.
  - Configurable X.509 keystores/truststores for mutual TLS (mTLS) downstream agent HTTP probes and task proxies.
- [ ] **Distributed Tracing with OpenTelemetry & W3C Trace Context:**
  - Native integration with Micrometer Tracing and OpenTelemetry Java agent.
  - End-to-end trace propagation across Hub HTTP endpoints, Virtual Thread workers, downstream agent proxies, and PostgreSQL JDBC statements.
- [ ] **Per-Agent Token Bucket Rate Limiting:**
  - Ingress and egress token bucket limiters to prevent Hub-initiated task proxy floods from overwhelming downstream agent endpoints.
  - Support for `429 Too Many Requests` responses with `Retry-After` headers.

---

## Medium-Term Milestones (v0.4.0 - v0.5.0)

Target: High-throughput streaming, horizontal clustering, and enterprise identity.

- [ ] **Server-Sent Events (SSE) & WebSocket Bidirectional Task Streaming:**
  - True streaming proxy for long-running LLM agent reasoning tokens.
  - Downstream chunked HTTP/2 streaming relayed to web UI and upstream callers without buffering.
- [ ] **Redis-Backed Distributed Coordination for Multi-Instance Hub Clustering:**
  - Distributed lock manager (Redlock / ShedLock) ensuring exactly-once execution of periodic health check cycles and log pruning across multiple Hub instances.
  - Redis Pub/Sub backplane for WebSocket STOMP message synchronization across clustered Hub nodes.
- [ ] **OAuth2 / OIDC Client Credentials Flow (RFC 6749):**
  - Support for machine-to-machine JWT authentication with automatic asymmetric key rotation (JWKS) to replace static API keys.

---

## Long-Term Milestones (v1.0.0+)

Target: Decentralized discovery, event meshes, and secure sandboxing.

- [ ] **Apache Kafka / CloudEvents Event Broker:**
  - High-throughput asynchronous task queue using CloudEvents v1.0 specifications for guaranteed delivery and dead-letter queue (DLQ) processing.
- [ ] **Decentralized Peer-to-Peer Agent Discovery (Kademlia DHT / Gossip):**
  - Peer-to-peer registry protocol enabling sovereign agent hubs to discover capabilities across organizational boundaries without a single point of failure.
- [ ] **WebAssembly (WASM) Agent Policy Sandboxing:**
  - Embedded Wasmtime/WasmEdge runtime to execute deterministic agent evaluation rules and client-side validation logic securely in-process.

---

## Release & Contribution Governance

- **Release Cadence:** Minor releases every 6 weeks. Patch releases deployed as needed for security fixes or regressions.
- **Architectural Changes:** Any proposal requiring schema changes or protocol modifications requires an Architectural Decision Record (ADR) submitted to `docs/adr/`.
- **Version Compatibility:** Strict Semantic Versioning 2.0.0. Breaking API changes trigger major version bumps.
