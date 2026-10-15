# A2A-Hub — Core Backend Service (v0.1.0-alpha)

> Core registry, semantic discovery, and task orchestration engine for the Agent2Agent (A2A) protocol.

---

## 1. Overview & Architectural Principles

The A2A-Hub backend is an enterprise-grade service designed to mediate communication, capability discovery, and task delegation between autonomous AI agents.

```
                            ┌────────────────────────┐
                            │    Frontend / REST     │
                            └───────────┬────────────┘
                                        │
                                        ▼
    ┌────────────────────────────────────────────────────────────────────────┐
    │                       Spring Boot 3.4.3 Core                           │
    │  Undertow Server · Virtual Threads (Project Loom) · Flyway Migrations   │
    ├──────────────────┬──────────────────┬──────────────────┬───────────────┤
    │  Agent Module    │  Health Monitor  │   Task Service   │ Orchestrator  │
    │ (Card & Skills)  │ (Virtual Thread  │ (Sync/Async Proxy│ (LangChain4j  │
    │                  │  Circuit Breaker)│  & Recovery)     │  Gemini 3.8)  │
    └────────┬─────────┴────────┬─────────┴────────┬─────────┴───────┬───────┘
             │                  │                  │                 │
             ▼                  ▼                  ▼                 ▼
    ┌─────────────────┐ ┌────────────────────────────────────────────────────┐
    │  PostgreSQL 16  │ │            Downstream Agent Network                │
    │   + pgvector    │ │  (SSRF & Zero-TOCTOU DNS Pinning via HttpClient 5) │
    └─────────────────┘ └────────────────────────────────────────────────────┘
```

### Key Architectural Invariants:
1. **Zero Connection Leakage during Network I/O**: Network calls (health probes, task delegation, card fetching) are executed on Java 21 Virtual Threads outside database transactions. HikariCP connections are never held across HTTP boundaries.
2. **Zero-TOCTOU SSRF Defense**: Remote agent connections are dispatched through Apache HttpClient 5 with a custom `DnsResolver` (`RestClientConfig`) that resolves and validates IP addresses (`SsrfValidator`) directly at socket level before TCP connection establishment.
3. **Crash Resilience & Durability**: Tasks stranded in `SUBMITTED` state during an unexpected restart are automatically marked as `FAILED` during application boot via `recoverOrphanedTasksOnStartup`.
4. **Calibrated Semantic Matching**: Vector embeddings in `pgvector` compute mathematically bounded cosine similarities `[0.0, 1.0]` without arbitrary hardcoded thresholds.

---

## 2. Module Breakdown

| Module Package | Description | Key Components |
| :--- | :--- | :--- |
| `dev.a2ahub.agent` | Agent entity persistence, discovery, and skill extraction | `Agent`, `AgentSkill`, `AgentRepository`, `AgentDiscoveryService`, `AgentRegistryService` |
| `dev.a2ahub.health` | Scheduled non-blocking health checks, retention cleanup, and reentrancy guards | `AgentHealthMonitor`, `AgentHealthPersistenceService`, `HealthCheck` |
| `dev.a2ahub.task` | Task proxy dispatch (sync `201` / async `202`), keyset pagination, and orphan recovery | `TaskService`, `TaskPersistenceService`, `Task`, `TaskStatus` |
| `dev.a2ahub.orchestration` | AI-assisted agent triage using Google Gemini with prompt isolation and mathematical fallback | `A2AOrchestrator`, `AiSuggestionDto` |
| `dev.a2ahub.security` | SSRF network validation, RFC 1918 filtering, and DNS resolution validation | `SsrfValidator`, `SecurityFilter` |
| `dev.a2ahub.events` | Real-time STOMP WebSocket messaging broker configuration and event publication | `WebSocketConfig`, `AgentHealthEvent`, `TaskStatusEvent` |
| `dev.a2ahub.config` | Virtual thread executor, HTTP client factories, and Resilience4j circuit breakers | `RestClientConfig`, `VirtualThreadConfig` |

---

## 3. Technology Stack

- **Java**: `21 LTS` (enforcing Virtual Threads via `spring.threads.virtual.enabled=true`)
- **Framework**: Spring Boot `3.4.3` on Red Hat Undertow
- **Database & Vectors**: PostgreSQL 16 with `pgvector` extension and Flyway database migrations
- **HTTP Client**: Apache HttpClient 5 (`org.apache.httpcomponents.client5:httpclient5:5.4.2`) with custom socket-level DNS resolver
- **LLM Orchestration**: LangChain4j `0.36.2` integrated with Google Gemini (`gemini-3.8-flash`)
- **Resilience**: Resilience4j Circuit Breakers, Timeouts, and atomic reentrancy guards
- **Architecture & Code Quality**: ArchUnit 1.3, JaCoCo, Bean Validation (Hibernate Validator)

---

## 4. Configuration Reference

Key properties configured in `src/main/resources/application.yml`:

| Property | Default | Purpose |
| :--- | :--- | :--- |
| `spring.threads.virtual.enabled` | `true` | Enables Project Loom Virtual Threads for Undertow and async executors |
| `a2ahub.security.ssrf.allow-private-networks` | `false` | When `false`, strictly rejects private (RFC 1918), loopback, and cloud metadata IPs |
| `a2ahub.health.interval-ms` | `30000` | Frequency of background agent health probes |
| `a2ahub.health.timeout-ms` | `3000` | HTTP probe socket timeout |
| `a2ahub.health.retention-days` | `7` | Retention window for historical probe records |
| `a2ahub.orchestration.model-name` | `gemini-3.8-flash` | Frontier Gemini model for agent triage |
| `a2ahub.orchestration.temperature` | `0.0` | Deterministic temperature for routing classification |

---

## 5. Building & Testing

### Prerequisites
- JDK 21+ (`JAVA_HOME` pointing to Java 21)
- Docker (required for Testcontainers PostgreSQL + pgvector tests)

### Build Commands

```bash
# Compile and package executable jar
./mvnw clean package -DskipTests

# Run full test suite (199 tests: Unit, Integration, ArchUnit, Testcontainers)
./mvnw test

# Generate JaCoCo code coverage report
./mvnw verify
```

### Architectural Guardrails (ArchUnit)
The backend enforces package layering and dependency rules via ArchUnit tests (`dev.a2ahub.architecture.ArchitectureTest`):
- Controllers must never directly access Repositories (must go through Services).
- Service methods executing network I/O must not be annotated with `@Transactional`.
- Circular dependencies between domain packages are strictly forbidden.
