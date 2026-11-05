package dev.a2ahub.task;

import com.sun.net.httpserver.HttpServer;
import dev.a2ahub.agent.Agent;
import dev.a2ahub.agent.AgentCard;
import dev.a2ahub.agent.AgentRepository;
import dev.a2ahub.config.RestClientConfig;
import dev.a2ahub.events.AgentEventPublisher;
import dev.a2ahub.security.SecurityProperties;
import dev.a2ahub.security.SsrfValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.task.AsyncTaskExecutor;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class TaskServiceCookieIsolationTest {

    private final AgentRepository agents = mock(AgentRepository.class);
    private final TaskPersistenceService persistence = mock(TaskPersistenceService.class);
    private final AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
    private final Deque<Runnable> workers = new ArrayDeque<>();
    private final List<ReceivedRequest> received = new CopyOnWriteArrayList<>();
    private HttpServer serverA;
    private HttpServer serverB;
    private Agent agentA;
    private Agent agentB;
    private TaskService service;

    @BeforeEach
    void setUp() throws Exception {
        serverA = startServer("A");
        serverB = startServer("B");
        agentA = agent("A", baseUrl(serverA));
        agentB = agent("B", baseUrl(serverB));
        when(agents.findById(agentA.getId())).thenReturn(Optional.of(agentA));
        when(agents.findById(agentB.getId())).thenReturn(Optional.of(agentB));
        when(persistence.createSubmittedTask(any(), any(), any())).thenAnswer(call ->
                dto(UUID.randomUUID(), TaskState.SUBMITTED, null, null));
        when(persistence.updateTaskResult(any(), any(), any(), any())).thenAnswer(call ->
                dto(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3)));
        doAnswer(call -> { workers.add(call.getArgument(0)); return null; })
                .when(executor).execute(any(Runnable.class));
        SecurityProperties properties = new SecurityProperties();
        properties.getSsrf().setAllowPrivateNetworks(true);
        SsrfValidator validator = new SsrfValidator(properties);
        RestClientConfig config = new RestClientConfig(validator);
        service = new TaskService(mock(TaskRepository.class), agents, persistence, validator,
                mock(AgentEventPublisher.class), config.restClientBuilder(), config.declaredTaskRestClient(), executor);
    }

    @AfterEach
    void tearDown() {
        if (serverA != null) serverA.stop(0);
        if (serverB != null) serverB.stop(0);
    }

    static Stream<Arguments> declarations() {
        return Stream.of(false, true).flatMap(async -> Stream.of(
                Arguments.of(async, "same-origin custom"),
                Arguments.of(async, "cross-origin custom"),
                Arguments.of(async, "same as fallback")));
    }

    @ParameterizedTest
    @MethodSource("declarations")
    void declaredEndpointsNeverReceiveLegacyCookies(boolean async, String declaration) {
        seedLegacyCookie();
        String endpoint = baseUrl(serverB) + (declaration.equals("same as fallback")
                ? "/tasks" : "/declared/tasks?mode=custom");
        if (declaration.equals("cross-origin custom")) {
            agentB.setUrl(baseUrl(serverA) + "/registration");
        }
        declare(agentB, endpoint);

        var result = service.submitTask(request(agentB), async);
        if (async) {
            assertThat(result.task().state()).isEqualTo("SUBMITTED");
            assertThat(received).isEmpty();
            // Neither endpoint nor transport may be recomputed from the mutable agent in the worker.
            agentB.setUrl(baseUrl(serverA));
            agentB.setAgentCard(null);
            workers.remove().run();
        } else {
            assertThat(result.task().state()).isEqualTo("COMPLETED");
        }

        assertThat(received).singleElement().satisfies(actual -> {
            assertThat(actual.server()).isEqualTo("B");
            assertThat(actual.uri()).isEqualTo(declaration.equals("same as fallback")
                    ? "/tasks" : "/declared/tasks?mode=custom");
            assertThat(actual.cookie()).isNull();
            assertThat(actual.authorization()).isNull();
            assertThat(actual.apiKey()).isNull();
        });
        verify(persistence).updateTaskResult(eq(result.task().id()), eq(TaskState.COMPLETED), any(), isNull());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void declaredResponsesDoNotSeedLaterDeclaredOrLegacyRequests(boolean async) {
        declare(agentB, baseUrl(serverB) + "/declared/tasks");
        dispatch(agentB, async);
        dispatch(agentB, async);
        agentB.setAgentCard(null);
        dispatch(agentB, async);

        assertThat(received).extracting(ReceivedRequest::uri)
                .containsExactly("/declared/tasks", "/declared/tasks", "/tasks");
        assertThat(received).allSatisfy(actual -> assertThat(actual.cookie()).isNull());
    }

    @Test
    void legacyFallbackAndBothPublicOverloadsKeepTheirCookiesAndHeaders() {
        seedLegacyCookie();
        agentB.setAgentCard(new AgentCard("B", "test", null, "1.0", List.of(), Map.of(), List.of("REST")));
        dispatch(agentB, false);
        service.executeDownstreamProxy(UUID.randomUUID(), "ctx-cookie", "B", agentB.getUrl(),
                Map.of("query", "synthetic"), "corr", "trace");
        service.executeDownstreamProxy(UUID.randomUUID(), "ctx-cookie", "B", agentB.getUrl() + "/",
                Map.of("query", "synthetic"), "corr", "trace", "API_KEY", "synthetic-api-key");

        assertThat(received).hasSize(3).allSatisfy(actual -> {
            assertThat(actual.uri()).isEqualTo("/tasks");
            assertThat(actual.cookie()).isEqualTo("legacy_session=synthetic-legacy");
        });
        assertThat(received.get(1).authorization()).isNull();
        assertThat(received.get(1).apiKey()).isNull();
        assertThat(received.get(2).apiKey()).isEqualTo("synthetic-api-key");
    }

    static Stream<Arguments> credentials() {
        return Stream.of(false, true).flatMap(async -> Stream.of(
                Arguments.of(async, "BEARER"), Arguments.of(async, "API_KEY")));
    }

    @ParameterizedTest
    @MethodSource("credentials")
    void sameOriginDeclaredEndpointsRetainExplicitCredentialsWithoutCookies(boolean async, String authType) {
        seedLegacyCookie();
        declare(agentB, baseUrl(serverB) + "/declared/tasks");
        agentB.setAuthType(authType);
        agentB.setAuthTokenEnc("synthetic-token");

        dispatch(agentB, async);

        assertThat(received).singleElement().satisfies(actual -> {
            assertThat(actual.cookie()).isNull();
            assertThat(actual.authorization()).isEqualTo(authType.equals("BEARER") ? "Bearer synthetic-token" : null);
            assertThat(actual.apiKey()).isEqualTo(authType.equals("API_KEY") ? "synthetic-token" : null);
        });
    }

    @ParameterizedTest
    @MethodSource("credentials")
    void authenticatedCrossOriginRejectionNeverAdmitsSchedulesOrSends(boolean async, String authType) {
        declare(agentB, baseUrl(serverA) + "/declared/tasks");
        agentB.setAuthType(authType);
        agentB.setAuthTokenEnc("synthetic-token");

        assertThatThrownBy(() -> service.submitTask(request(agentB), async))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("registered agent origin");

        verifyNoInteractions(persistence, executor);
        assertThat(received).isEmpty();
        assertThat(workers).isEmpty();
    }

    @Test
    void asyncFallbackRetainsCapturedEndpointAndLegacyTransport() {
        seedLegacyCookie();
        var result = service.submitTask(request(agentB), true);
        assertThat(result.task().state()).isEqualTo("SUBMITTED");
        assertThat(received).isEmpty();
        declare(agentB, baseUrl(serverA) + "/declared/tasks");
        agentB.setUrl(baseUrl(serverA));

        workers.remove().run();

        assertThat(received).singleElement().satisfies(actual -> {
            assertThat(actual.server()).isEqualTo("B");
            assertThat(actual.uri()).isEqualTo("/tasks");
            assertThat(actual.cookie()).isEqualTo("legacy_session=synthetic-legacy");
        });
    }

    private HttpServer startServer(String name) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            var headers = exchange.getRequestHeaders();
            received.add(new ReceivedRequest(name, exchange.getRequestURI().toString(), headers.getFirst("Cookie"),
                    headers.getFirst("Authorization"), headers.getFirst("X-API-Key")));
            if (name.equals("A") && exchange.getRequestURI().getPath().equals("/tasks")) {
                exchange.getResponseHeaders().add("Set-Cookie", "legacy_session=synthetic-legacy; Path=/");
            } else if (name.equals("B") && exchange.getRequestURI().getPath().equals("/declared/tasks")) {
                exchange.getResponseHeaders().add("Set-Cookie", "declared_session=synthetic-declared; Path=/");
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] response = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static Agent agent(String name, String url) {
        Agent agent = new Agent();
        agent.setName(name);
        agent.setUrl(url);
        agent.setStatus("HEALTHY");
        return agent;
    }

    private void declare(Agent agent, String endpoint) {
        agent.setAgentCard(new AgentCard(agent.getName(), "test", null, "1.0", List.of(), Map.of(), List.of(endpoint)));
    }

    private TaskService.SubmitTaskRequest request(Agent agent) {
        return new TaskService.SubmitTaskRequest(agent.getId(), "ctx-cookie", Map.of("query", "synthetic"));
    }

    private void dispatch(Agent agent, boolean async) {
        var result = service.submitTask(request(agent), async);
        if (async) {
            assertThat(result.task().state()).isEqualTo("SUBMITTED");
            workers.remove().run();
        } else {
            assertThat(result.task().state()).isEqualTo("COMPLETED");
        }
    }

    private void seedLegacyCookie() {
        dispatch(agentA, false);
        assertThat(received).singleElement().satisfies(actual -> assertThat(actual.cookie()).isNull());
        received.clear();
    }

    private TaskService.TaskDto dto(UUID taskId, TaskState state, Map<String, Object> response, String error) {
        return new TaskService.TaskDto(taskId, agentB.getId(), "B", "ctx-cookie", state.name(), Map.of(),
                response, error, ZonedDateTime.now(), ZonedDateTime.now());
    }

    private record ReceivedRequest(String server, String uri, String cookie, String authorization, String apiKey) {}
}
