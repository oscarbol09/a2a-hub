# CalculatorAgent — A2A Reference Agent (v0.1.0-alpha)

> Reference implementation of a computational and mathematical microservice agent compatible with the Agent2Agent (A2A) Protocol v1.0.

---

## 1. Overview

`CalculatorAgent` demonstrates numerical problem solving and mathematical reasoning under the A2A-Hub ecosystem. It strictly isolates mathematical computation from arbitrary general queries.

- **Default Port**: `8082`
- **Framework**: Spring Boot `3.4.3` + LangChain4j `0.36.2`
- **LLM Engine**: Google Gemini (`gemini-3.8-flash`) via `GoogleAiGeminiChatModel` (with deterministic mock fallback when `GEMINI_API_KEY` is omitted)

---

## 2. A2A Protocol Endpoints

### 2.1 Agent Card Discovery
```http
GET /.well-known/agent-card.json
```
Returns the agent's identity, version, semantic description, supported interfaces, and skills schema as specified in `AGENTS.md`.

### 2.2 Task Execution Contract
```http
POST /tasks
Content-Type: application/json
```
**Request Body**:
```json
{
  "query": "What is 42 * 17 + sqrt(144)?",
  "contextId": "ctx-calc-01"
}
```

**Successful Response (`200 OK`)**:
```json
{
  "status": "ok",
  "result": "726",
  "contextId": "ctx-calc-01"
}
```

---

## 3. Security & Domain Guardrails

1. **Input Length Validation**: Queries are constrained to `@Size(max = 500)` characters to prevent prompt bloat attacks.
2. **Prompt Injection Isolation (OWASP LLM01)**: Inputs are wrapped within `<user_query>...</user_query>` XML boundaries.
3. **Domain Pinning & Refusal**: Non-mathematical queries are deterministically rejected with:
   `"REFUSAL: I am a specialized calculator agent and can only answer questions related to mathematics, arithmetic, and numerical reasoning."`
4. **Hardened Container Runtime**: Docker container runs as unprivileged non-root user (`USER 10001:10001`) with read-only root options and built-in healthchecks.

---

## 4. Development & Testing

```bash
# Compile and run unit tests
./mvnw test

# Run standalone
./mvnw spring-boot:run
```
