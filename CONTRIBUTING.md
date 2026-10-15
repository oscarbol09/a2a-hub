# Contributing to A2A-Hub

Guidelines for reporting bugs, submitting features, and opening pull requests.

## Workflow

1. Check [open issues](https://github.com/oscarbol09/a2a-hub/issues) before opening a new ticket.
2. Fork the repository and create a feature branch off `main`:
   ```bash
   git checkout -b feat/discovery-filter
   ```
3. Ensure local tests pass before opening a Pull Request:
   ```bash
   # Backend
   cd backend && ./mvnw test

   # Microservice Agents
   cd weather-agent && ./mvnw test
   cd calculator-agent && ./mvnw test

   # Frontend
   cd frontend && npm test && npm run build
   ```

## Engineering Standards

### Backend (Java 21 · Spring Boot 3.4.3)
- **Virtual Threads Hygiene:** Do not hold `synchronized` monitors across I/O or network calls. Use `ReentrantLock` when synchronization is mandatory to avoid Loom carrier thread pinning.
- **Data Access:** Validate entity boundaries; store dynamic A2A spec payloads in PostgreSQL `JSONB` with `@JdbcTypeCode(SqlTypes.JSON)`.
- **Testing:** Unit tests must use JUnit 5 and AssertJ. Controller tests must use `@WebMvcTest` with MockMvc. Do not commit tautological tests or empty assertion mocks.

### Frontend (Vue 3.5 · TypeScript · Tailwind CSS)
- **Composition API:** Use `<script setup lang="ts">`.
- **Strict Typing:** Define explicit interfaces for all API response payloads and component props in `src/services/api.ts`.
- **Styling:** Use semantic Tailwind classes. Keep component layout modular.

## Commit Message Format

We follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <short summary>

[optional body explaining WHY the change was made]
```

**Allowed types:** `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `chore`.

Examples:
- `feat(registry): add SSRF validation to agent registration endpoint`
- `test(discovery): add unit test matrix for cosine similarity ranking`
- `fix(ws): reconnect WebSocket channel on connection drop`

## Code of Conduct

All contributors are expected to adhere to the [Contributor Covenant](CODE_OF_CONDUCT.md).
