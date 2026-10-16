# A2A-Hub — Frontend Dashboard (v0.1.0-alpha)

> Reactive web dashboard for the Agent2Agent (A2A) protocol hub. Built with Vue 3.5, TypeScript, Tailwind CSS v4, Pinia, and real-time STOMP WebSockets.

---

## 1. Overview & Architecture

The A2A-Hub Frontend provides an operator and developer interface for discovering, inspecting, orchestrating, and monitoring autonomous AI agents registered with A2A-Hub.

```
                    ┌──────────────────────────────────────────────┐
                    │               Vue 3.5 Client                 │
                    │  (Vite 8 · TypeScript · Tailwind CSS v4)      │
                    └───────┬──────────────────────────────┬───────┘
                            │ REST (Axios)                 │ STOMP / WS
                            ▼                              ▼
                    ┌──────────────────────────────────────────────┐
                    │            A2A-Hub Core Backend              │
                    │      (Spring Boot 3.4.3 / Undertow)          │
                    └──────────────────────────────────────────────┘
```

### Key Capabilities:
- **Registry & Registration**: Search, filter, inspect, and dynamically onboard agents via `.well-known/agent-card.json`.
- **Hybrid Discovery**: Semantic similarity search and tag/skill exploration backed by PostgreSQL `pgvector`.
- **AI Task Orchestration**: Interactive task submission with automated triage suggestions powered by Google Gemini (`gemini-3.8-flash`) and fallback to cosine similarity.
- **Real-time Health & Task Streaming**: Live status and execution updates via STOMP WebSockets (`/topic/agents/status` and `/topic/tasks`).

---

## 2. Technology Stack

| Layer | Technology | Version | Purpose |
| :--- | :--- | :--- | :--- |
| **Framework** | Vue.js | `3.5.x` | Reactive UI framework using `<script setup lang="ts">` |
| **Language** | TypeScript | `~6.0.x` | Strict type safety and schema validation |
| **Build Tool** | Vite | `8.x` | Rapid HMR and optimized production bundling |
| **Styling** | Tailwind CSS | `v4.x` | Modern utility-first CSS via `@tailwindcss/vite` |
| **State Management** | Pinia | `4.x` | Modular, type-safe stores (`agentStore`, `taskStore`) |
| **Routing** | Vue Router | `5.x` | Client-side routing with route-level code splitting |
| **Real-time Protocol** | `@stomp/stompjs` | `7.3.x` | STOMP client over WebSocket with reconnect logic |
| **Icons** | Lucide Vue Next | `1.x` | Consistent SVG icon library |
| **Testing Engine** | Vitest | `5.x` | Fast unit and integration test runner |
| **Network Mocking** | MSW | `2.x` | Mock Service Worker for deterministic HTTP mocks |
| **Generative Testing** | fast-check | `4.x` | Property-based testing for utility robustness |

---

## 3. Directory Structure

```
frontend/src/
├── assets/             # Static brand assets and SVGs
├── components/         # Reusable presentation components
│   ├── AgentCard.vue       # Agent display card with health badge and skills
│   ├── SkillBadge.vue      # Visual badge for indexed agent skills
│   ├── TaskTimeline.vue    # Interactive chronological task progress tracker
│   └── __tests__/          # Component unit and snapshot tests
├── lib/                # Shared utilities (class merger cn, formatting)
├── router/             # Vue Router configuration and route guards
├── services/           # Network layer
│   ├── api.ts              # Axios client with interceptors and error handling
│   ├── websocket.ts        # STOMP broker client lifecycle management
│   └── __tests__/          # Network contract and property-based tests
├── stores/             # Pinia state stores
│   ├── agentStore.ts       # Registry, search state, and health stream subscriptions
│   ├── taskStore.ts        # Task submission, cursor pagination, and live updates
│   └── __tests__/          # Pinia store unit tests
├── views/              # Top-level route views
│   ├── AgentRegistryView.vue  # Agent catalog and registration dialog
│   ├── AgentDetailView.vue    # Comprehensive agent card and metric inspector
│   ├── DiscoverView.vue       # Semantic and tag-based agent discovery
│   ├── OrchestrateView.vue    # AI triage and task execution workspace
│   ├── NotFoundView.vue       # 404 error page
│   └── __tests__/             # View integration tests
├── App.vue             # Root application shell with navigation bar
├── main.ts             # Application bootstrapper and plugin registration
└── style.css           # Global stylesheet and Tailwind directives
```

---

## 4. Key Modules & State Stores

### `agentStore.ts`
- Manages cached agent profiles, active filters, search queries, and selected agent state.
- Subscribes to STOMP destination `/topic/agents/status`.
- Handles incoming health updates with client-side deduplication to prevent UI re-render thrashing.

### `taskStore.ts`
- Dispatches synchronous (`201 Created`) and asynchronous (`202 Accepted`) tasks to the Hub.
- Maintains task history with cursor-based pagination.
- Subscribes to STOMP destination `/topic/tasks` for live progression from `SUBMITTED` -> `WORKING` -> `COMPLETED` / `FAILED`.

### `websocket.ts`
- Encapsulates `@stomp/stompjs` client initialization.
- Implements exponential backoff reconnect strategies (1s, 2s, 4s, up to 10s max).
- Exposes connection status reactive hooks for UI connection status indicators.

---

## 5. Development & Verification

### Prerequisites
- Node.js `20.x` or `22.x` (LTS recommended)
- npm `10.x` or pnpm / yarn equivalent

### Getting Started

```bash
# 1. Install dependencies
npm install

# 2. Run local development server (http://localhost:5173)
npm run dev

# 3. Type-check and build for production
npm run build

# 4. Preview production build locally
npm run preview
```

### Running Tests

The test suite covers component behaviors, store mutations, API contracts (MSW), and invariants (property-based testing with `fast-check`):

```bash
# Run all unit and integration tests (148 tests across 11 suites)
npm test

# Run tests in watch mode during development
npm run test:watch

# Generate code coverage report
npm run test:coverage
```

---

## 6. Environment Configuration

Create a `.env` or `.env.local` file in the `frontend` root to customize connection targets:

| Variable | Default Value | Description |
| :--- | :--- | :--- |
| `VITE_API_URL` | `http://localhost:8080/api/v1` | Target URL for A2A-Hub backend REST endpoints |
| `VITE_WS_URL` | `ws://localhost:8080/ws` | WebSocket endpoint for STOMP status/task subscriptions |
| `VITE_API_KEY` | *(optional)* | Optional API key for authenticated endpoints and WebSocket STOMP CONNECT (`X-API-Key`) |

---

## 7. Quality & Governance Standards

- **Zero-Tolerance for Type Errors**: `vue-tsc -b` runs before every production build; unsafe `any` casts are strictly avoided.
- **Contract-First Testing**: Component and store tests verify behavior against the A2A Protocol v1.0 specifications using deterministic Mock Service Worker (MSW) handlers.
- **Accessible & Responsive**: Designed with mobile-responsive layouts, accessible ARIA attributes on interactive badges and modal controls, and semantic color status tokens (`HEALTHY` = Emerald, `DEGRADED` = Amber, `OFFLINE` = Rose).
