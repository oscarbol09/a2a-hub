# WeatherAgent — A2A Reference Agent (v0.1.0-alpha)

> Reference implementation of an autonomous microservice agent compatible with the Agent2Agent (A2A) Protocol v1.0.

---

## 1. Overview

`WeatherAgent` demonstrates how an external service integrates with A2A-Hub. It provides meteorological forecasts and answers weather-related inquiries while enforcing strict domain-boundary guardrails and prompt-injection defenses.

- **Default Port**: `8081`
- **Framework**: Spring Boot `3.4.3` + Spring AI `1.0.0-M1`
- **LLM Engine**: Google Gemini (`gemini-3.8-flash`) via `ChatClient` (with deterministic mock fallback when `GEMINI_API_KEY` is omitted)

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
  "query": "What is the meteorological forecast for Bogota tomorrow?",
  "contextId": "ctx-weather-01"
}
```

**Successful Response (`200 OK`)**:
```json
{
  "status": "ok",
  "result": "Expected temperature in Bogota is 19°C with intermittent cloud cover and light showers.",
  "contextId": "ctx-weather-01"
}
```

---

## 3. Security & Domain Guardrails

1. **Prompt Injection Isolation (OWASP LLM01)**: Untrusted user input is enclosed inside `<user_query>...</user_query>` tags and instructed never to override system prompt directives.
2. **Domain Pinning & Refusal**: The agent deterministically rejects non-weather queries (e.g. general conversation, code execution) with:
   `"REFUSAL: I am a specialized weather agent and can only answer questions related to weather."`
3. **Hardened Container Runtime**: Docker container runs as unprivileged non-root user (`USER 10001:10001`) with read-only root options and built-in healthchecks.

---

## 4. Development & Testing

```bash
# Compile and run unit tests
./mvnw test

# Run standalone
./mvnw spring-boot:run
```
