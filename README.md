<p align="center">
  <img src="https://raw.githubusercontent.com/oscarbol09/a2a-hub/main/frontend/public/favicon.svg" alt="A2A-Hub Logo" width="96">
</p>

<h1 align="center">A2A-Hub</h1>

<p align="center">
  <strong>Agent Discovery Registry & Orchestration Hub for the Agent2Agent (A2A) Protocol (v0.1.0-alpha)</strong>
</p>

<p align="center">
  <a href="https://github.com/oscarbol09/a2a-hub/actions/workflows/ci.yml"><img src="https://github.com/oscarbol09/a2a-hub/actions/workflows/ci.yml/badge.svg" alt="CI Pipeline"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg" alt="License: Apache 2.0"></a>
  <a href="https://spring.io/projects/spring-boot"><img src="https://img.shields.io/badge/Spring_Boot-3.4.3-6DB33F.svg" alt="Spring Boot 3.4.3"></a>
  <a href="https://www.oracle.com/java/technologies/downloads/#java21"><img src="https://img.shields.io/badge/Java-21_LTS-007396.svg" alt="Java 21 LTS"></a>
  <a href="https://vuejs.org/"><img src="https://img.shields.io/badge/Vue-3.5-4FC08D.svg" alt="Vue 3.5"></a>
  <a href="https://github.com/a2aproject/A2A"><img src="https://img.shields.io/badge/Protocol-A2A_v1.0-orange.svg" alt="A2A Protocol v1.0"></a>
</p>

---

## Why This Exists

The [Agent2Agent (A2A) Protocol](https://github.com/a2aproject/A2A) standardizes how autonomous agents publish capability manifests and negotiate work. While client-side SDKs handle point-to-point communication between known endpoints, multi-agent deployments require an infrastructure registry to:

1. **Resolve agent endpoints dynamically** instead of hardcoding service hostnames and ports into client agents.
2. **Monitor availability and latency** via non-blocking background probes, eliminating health polling overhead from calling agents.
3. **Execute semantic capability routing** so natural language task requests match the most qualified agent without static rule engines.

This project addresses the operational registry gap discussed in [a2aproject/a2a-java#683](https://github.com/a2aproject/a2a-java/issues/683), delivering an extensible reference hub and monitoring dashboard built on Java 21, Spring Boot 3.4.3, and PostgreSQL 16.

---

## Architecture & Concurrency Model

```
┌────────────────────────────────────────────────────────────────────────┐
│                              A2A-Hub                                   │
│                                                                        │
│   ┌───────────────┐     ┌────────────────┐     ┌───────────────────┐   │
│   │  Vue 3.5 UI   │◄───►│  WebSocket     │     │  REST Endpoints   │   │
│   │  (Vite / SPA) │     │  /ws/agents    │     │  /api/v1/*        │   │
│   └───────┬───────┘     └────────┬───────┘     └─────────┬─────────┘   │
│           │                      │                       │             │
│   ┌───────▼──────────────────────▼───────────────────────▼─────────┐   │
│   │             Spring Boot 3.4 (Java 21 Virtual Threads)          │   │
│   │                                                                │   │
│   │  • SsrfValidator          • ApiKeyAuthFilter (Constant-time)   │   │
│   │  • AgentRegistryService   • AgentDiscoveryService (pgvector)   │   │
│   │  • AgentHealthMonitor     • TaskService (RFC 7240 Async Proxy) │   │
│   │  • A2AOrchestrator        • AgentBootstrapService              │   │
│   └──────────────────────────────┬─────────────────────────────────┘   │
│                                  │ HikariCP (Connection Pool = 20)    │
│   ┌──────────────────────────────▼─────────────────────────────────┐   │
│   │                PostgreSQL 16 + pgvector                        │   │
│   │  • agents (UUIDv7, lock_version, JSONB card, vector(768))      │   │
│   │  • agent_skills (GIN tags index, foreign key B-tree index)     │   │
│   │  • health_checks (Partial covering latency index, FIFO prune)  │   │
│   │  • tasks (Keyset pagination index, TOAST covering index)       │   │
│   └────────────────────────────────────────────────────────────────┘   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Outbound HTTP / JSON-RPC Probes
                                    │ (Virtual Threads, Zero DB Holds)
                                    ▼
       ┌────────────────────────────┬────────────────────────────┐
       │                            │                            │
       ▼                            ▼                            ▼
┌─────────────────────────┐  ┌─────────────────────────┐  ┌─────────────────────────┐
│ weather-agent (8081)    │  │ calculator-agent (8082) │  │ External Agents         │
│ • Spring AI 1.0         │  │ • LangChain4j           │  │ • A2A v1.0 Spec         │
│ • /.well-known/agent... │  │ • /.well-known/agent... │  │ • /.well-known/agent... │
│ • POST /tasks           │  │ • POST /tasks           │  │ • POST /tasks           │
└─────────────────────────┘  └─────────────────────────┘  └─────────────────────────┘
```

---

## Core Engineering Implementations

- **RFC 9562 UUIDv7 Clustered Primary Keys:** Entity IDs are generated via SQL `uuid_generate_v7()` using `clock_timestamp()` and cryptographic entropy. Monotonic time ordering preserves sequential B-tree insert locality and reduces random page splits.
- **Optimistic Concurrency Control:** Hibernate `@Version lock_version` on `agents` and `tasks` protects against race conditions and concurrent overwrite hazards.
- **Socket-Level SSRF & DNS Pinning Defenses:** `SsrfValidator` and Apache HttpClient 5 integrate at the socket resolution level, validating all resolved IP addresses before connection. Prohibits loopback, carrier-grade NAT (`100.64.0.0/10`), link-local, RFC 1918 subnets, cloud metadata (`169.254.169.254`), and IPv6 ULA (`fc00::/7`), mitigating Time-of-Check to Time-of-Use (TOCTOU) DNS rebinding risks.
- **Constant-Time Authentication & Path Normalization:** `ApiKeyAuthFilter` uses Spring MVC path canonicalization and constant-time byte comparisons (`MessageDigest.isEqual`) to prevent CWE-208 timing side channels.
- **Non-Blocking Virtual Thread Health Probing:** Probes execute across Java 21 Virtual Threads without leasing database connections during outbound network I/O. Results are written via dedicated persistence workers in short `<2ms` `@Transactional(REQUIRES_NEW)` scopes, guarded against overlapping probe cycles.
- **Startup Task Recovery:** Reconciles in-flight tasks orphaned in `SUBMITTED` state across server crashes or node restarts, transitioning them safely to `FAILED`.
- **Chunked FIFO Table Pruning:** Scheduled maintenance purges historical telemetry with non-blocking CTE queries (`WITH doomed AS ... DELETE ... LIMIT 5000`) and 50ms pauses, preventing MVCC table bloat and WAL spikes.
- **RFC 7240 Asynchronous Task Proxying:** Supports both immediate synchronous execution (`201 Created`) and asynchronous delegation (`202 Accepted` with `Location` header and `Preference-Applied: respond-async`).
- **Keyset Cursor Pagination:** Queries use composite B-tree indexes `(agent_id, id DESC)` with covering columns to bypass PostgreSQL TOAST table reads entirely during pagination.
- **AI Triage with Invariant Validation Gate:** `A2AOrchestrator` uses LangChain4j and Google Gemini (`gemini-3.8-flash`) with temperature 0.0, 1000-char input bounding, and XML delimiters (`<candidate_pool>`, `<user_task>`). A post-routing invariant gate guarantees that selected agent UUIDs exist in the retrieved pool; if violated or unconfigured, the system falls back to `pgvector` cosine similarity with real, mathematically bounded similarity scores.
- **Reference Microservices:** Containerized reference agents (`weather-agent` on Spring AI 1.1, `calculator-agent` on LangChain4j) with domain refusal constraints and automated seed bootstrap discovery (`a2ahub.bootstrap.agents`).

---

## Current Status & Operational Scope

> [!NOTE]
> **Status:** Alpha v0.1.0 (Advanced Functional Prototype & Reference Implementation)
> 
> - **A2A Protocol Conformance:** Uses native domain mapping conforming to the [A2A Protocol Specification v1.0](AGENTS.md). Direct integration with the official `a2a-java-sdk-client` will be completed once published to Maven Central.
> - **Deployment Topology:** Currently single-node with in-process Virtual Thread task dispatching. Resilient task recovery is active on node reboot. Distributed coordination (ShedLock, Kafka/durable outbox, multi-tenant RBAC) is planned on the [ROADMAP.md](ROADMAP.md).
> - **Network Security:** Production deployments must keep `A2AHUB_SECURITY_SSRF_ALLOW_PRIVATE_NETWORKS=false` (or `A2A_HUB_ALLOW_PRIVATE_NETWORKS=false`) to enforce socket-level SSRF defenses.

---

## Quickstart

### Prerequisites
- Docker Engine 24+ & Docker Compose v2+
- Java 21 JDK (optional, if compiling outside Docker)
- Node.js 20+ (optional, for frontend development)

### 1. Run Complete Infrastructure Stack
```bash
git clone https://github.com/oscarbol09/a2a-hub.git
cd a2a-hub

# Build and start PostgreSQL (pgvector:pg16), Backend, WeatherAgent, and CalculatorAgent
docker-compose up -d --build

# Verify healthy status across containers
docker-compose ps
```

The services will be available at:
- **A2A-Hub Backend API:** `http://localhost:8080` (Swagger UI: `http://localhost:8080/swagger-ui.html`)
- **Weather Reference Agent:** `http://localhost:8081` (`/.well-known/agent-card.json`)
- **Calculator Reference Agent:** `http://localhost:8082` (`/.well-known/agent-card.json`)
- **PostgreSQL Database:** `localhost:5432`

### 2. Run Web Dashboard (Development)
```bash
cd frontend
npm install
npm run dev
```
Open `http://localhost:5173` to access the registry, live topology monitor, and orchestration console.

---

## Production Configuration Reference

| Environment Variable | Property Key | Default Value | Description |
| :--- | :--- | :--- | :--- |
| `DB_HOST` | `spring.datasource.url` | `localhost` | PostgreSQL host. |
| `DB_PORT` | `spring.datasource.url` | `5432` | PostgreSQL port. |
| `DB_NAME` | `spring.datasource.url` | `a2ahub` | Database name. |
| `DB_USER` | `spring.datasource.username` | `postgres` | Database username. |
| `DB_PASSWORD` | `spring.datasource.password` | `postgres` | Database password. |
| `A2A_HUB_API_KEY` | `a2ahub.security.api-key` | *(empty)* | If set, enforces `X-API-Key` or `Authorization: Bearer` on mutating endpoints. If empty, local open mode is enabled. |
| `A2A_HUB_ENCRYPTION_KEY` | `a2ahub.security.encryption-key` | *(dev fallback)* | Master secret for AES-256-GCM token encryption. Must be at least 16 characters in production. |
| `A2A_HUB_REQUIRE_AUTH_READS` | `a2ahub.security.require-auth-for-reads` | `false` | If `true`, requires API key authentication for read-only GET endpoints. |
| `A2A_HUB_ALLOWED_ORIGINS` | `a2ahub.security.allowed-origins` | `http://localhost:5173,http://localhost:3000,http://127.0.0.1:5173,http://127.0.0.1:3000` | Comma-separated list of allowed CORS origins for browser clients. Configure this variable when deploying the Hub on custom domains, intranet hosts, or staging environments. |
| `A2AHUB_SECURITY_SSRF_ALLOW_PRIVATE_NETWORKS` / `A2A_HUB_ALLOW_PRIVATE_NETWORKS` | `a2ahub.security.ssrf.allow-private-networks` | `false` | If `false`, blocks loopback, private RFC 1918, link-local, and cloud metadata addresses. Set `true` only for containerized test networks. |
| `GEMINI_API_KEY` | `a2ahub.gemini.api-key` | *(empty)* | Google Gemini API key for AI orchestration triage. If empty, orchestrator falls back to vector similarity. |
| `A2A_HUB_HEALTH_INTERVAL_MS` | `a2ahub.health.interval-ms` | `30000` | Probing frequency in milliseconds across all registered agents. |
| `A2A_HUB_HEALTH_RETENTION_HOURS` | `a2ahub.health.retention-hours` | `48` | Retention period for health check logs. Hourly cron executes chunked batch CTE pruning for records exceeding this threshold. |
| `A2A_HUB_BOOTSTRAP_AGENTS` | `a2ahub.bootstrap.agents` | *(empty)* | Comma-delimited list of seed URLs automatically registered and periodically reconciled on boot. |

---

## Core API Reference

### 1. Register an Agent (`POST /api/v1/agents`)
Fetches and validates the agent's remote `agent-card.json`:
```bash
curl -X POST http://localhost:8080/api/v1/agents \
  -H "Content-Type: application/json" \
  -d '{"url": "http://weather-agent:8081"}'
```
Response (`201 Created`):
```json
{
  "id": "01927e10-6fa2-7c88-990a-c184742e9432",
  "name": "WeatherAgent",
  "description": "Real-time weather forecasting and meteorological assistant",
  "url": "http://weather-agent:8081",
  "version": "1.0.0",
  "status": "HEALTHY",
  "authType": "NONE",
  "registeredAt": "2026-10-11T18:00:00Z"
}
```

### 2. Semantic Hybrid Discovery (`GET /api/v1/discover/semantic`)
Performs cosine distance search over 768-dimensional skill embeddings via `pgvector`:
```bash
curl -G "http://localhost:8080/api/v1/discover/semantic" \
  --data-urlencode "q=meteorological forecast and atmospheric conditions" \
  --data-urlencode "limit=5"
```

### 3. AI Orchestration Triage (`POST /api/v1/orchestration/suggest`)
Evaluates candidate agents using Gemini with prompt injection isolation and automatic fallback:
```bash
curl -X POST http://localhost:8080/api/v1/orchestration/suggest \
  -H "Content-Type: application/json" \
  -d '{"taskDescription": "What is the expected temperature in Bogota tomorrow?"}'
```
Response (`200 OK`):
```json
{
  "agentId": "01927e10-6fa2-7c88-990a-c184742e9432",
  "agentName": "WeatherAgent",
  "reasoning": "The user query requests temperature and meteorological forecast for Bogota, directly matching WeatherAgent.",
  "source": "LLM_REASONING",
  "fallback": false,
  "confidenceScore": 0.95,
  "fallbackReason": null
}
```

### 4. Synchronous Task Execution (`POST /api/v1/tasks`)
Proxies task to agent endpoint and returns result synchronously:
```bash
curl -X POST http://localhost:8080/api/v1/tasks \
  -H "Content-Type: application/json" \
  -d '{
    "agentId": "01927e10-6fa2-7c88-990a-c184742e9432",
    "contextId": "ctx-9812",
    "payload": {
      "query": "Check current weather in Paris"
    }
  }'
```
Response (`201 Created`):
```json
{
  "id": "01927e12-8bb1-7a11-b021-998811223344",
  "agentId": "01927e10-6fa2-7c88-990a-c184742e9432",
  "agentName": "WeatherAgent",
  "contextId": "ctx-9812",
  "state": "COMPLETED",
  "request": { "query": "Check current weather in Paris" },
  "response": { "status": "ok", "result": "18°C, Partly Cloudy", "contextId": "ctx-9812" },
  "errorDetail": null,
  "createdAt": "2026-10-11T18:05:00Z",
  "updatedAt": "2026-10-11T18:05:01Z"
}
```

### 5. Asynchronous Task Delegation via RFC 7240 (`POST /api/v1/tasks`)
Dispatches long-running task to Virtual Thread worker, returning `202 Accepted` immediately:
```bash
curl -i -X POST http://localhost:8080/api/v1/tasks \
  -H "Prefer: respond-async" \
  -H "Content-Type: application/json" \
  -d '{
    "agentId": "01927e10-6fa2-7c88-990a-c184742e9432",
    "contextId": "ctx-async-01",
    "payload": {
      "query": "Synthesize a 10-day meteorological precipitation forecast"
    }
  }'
```
Response (`202 Accepted`):
```http
HTTP/1.1 202 Accepted
Location: /api/v1/tasks/01927e13-4cd2-7b22-a890-554433221100
Preference-Applied: respond-async
Content-Type: application/json

{
  "id": "01927e13-4cd2-7b22-a890-554433221100",
  "agentId": "01927e10-6fa2-7c88-990a-c184742e9432",
  "agentName": "WeatherAgent",
  "contextId": "ctx-async-01",
  "state": "SUBMITTED",
  "request": { "query": "Synthesize a 10-day meteorological precipitation forecast" },
  "response": null,
  "errorDetail": null,
  "createdAt": "2026-10-11T18:06:00Z",
  "updatedAt": "2026-10-11T18:06:00Z"
}
```

### 6. Keyset Cursor Pagination on Tasks (`GET /api/v1/tasks/agent/{agentId}`)
High-speed seek pagination bypassing offset scans and TOAST storage:
```bash
curl -G "http://localhost:8080/api/v1/tasks/agent/01927e10-6fa2-7c88-990a-c184742e9432" \
  --data-urlencode "cursorId=01927e13-4cd2-7b22-a890-554433221100" \
  --data-urlencode "size=20"
```

---

## Architectural Decisions & Trade-offs

- **Java 21 Virtual Threads over Reactive Streams:** We use Project Loom (`spring.threads.virtual.enabled=true`) with synchronous `RestClient`. This provides sequential, debuggable call stacks and standard thread-local contexts while sustaining thousands of concurrent outbound HTTP probes without carrier-thread exhaustion or reactive library complexity.
- **PostgreSQL JSONB + pgvector over Dedicated Vector Databases:** Storing the raw `AgentCard` in `JSONB` alongside 768-dimensional `vector` embeddings in PostgreSQL eliminates distributed transactions between relational and vector stores. Skill and tag updates remain ACID-compliant in a single transaction.
- **Database Transaction Decoupling:** Outbound network calls (HTTP health checks and task delegations) execute outside database transactions. Connection pool leases are held strictly during database writes (<2ms), protecting the HikariCP pool from starvation during slow downstream network requests.

---

## Known Limitations

- **Authentication Protocol:** Downstream agent authentication currently supports static API tokens (`Bearer` or `X-API-Key`). Mutual TLS (mTLS) certificate exchange is scheduled for milestone v0.2.0.
- **Task Streaming:** Task responses are returned upon completion. Chunked token streaming via Server-Sent Events (SSE) for long-running reasoning agents is scheduled for milestone v0.5.0.
- **Single-Node Health Coordinator:** Periodic health checks and FIFO table pruning run within a single Hub instance. Distributed scheduling across horizontal clusters via Redis distributed locking is tracked in milestone v0.5.0.

---

## Related Documentation

- [Agent Developer & Interoperability Guide](AGENTS.md): Full technical specification for implementing compatible agents.
- [Engineering Roadmap](ROADMAP.md): Release schedule and future architectural milestones.
- [Changelog](CHANGELOG.md): Version history and detailed record of changes.
- [Contributing Guidelines](CONTRIBUTING.md): Workflow, testing rules, and Conventional Commits format.

---

## License

[Apache License 2.0](LICENSE). Free for personal and commercial use.