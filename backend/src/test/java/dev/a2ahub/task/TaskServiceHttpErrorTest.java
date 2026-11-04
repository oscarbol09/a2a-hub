package dev.a2ahub.task;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import dev.a2ahub.agent.Agent;
import dev.a2ahub.agent.AgentCard;
import dev.a2ahub.agent.AgentRepository;
import dev.a2ahub.config.RestClientConfig;
import dev.a2ahub.events.AgentEventPublisher;
import dev.a2ahub.security.SecurityProperties;
import dev.a2ahub.security.SsrfValidator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskServiceHttpErrorTest {

    static Stream<Arguments> customPathResponses() {
        return Stream.of(Arguments.of(false, 200), Arguments.of(true, 200),
                Arguments.of(false, 500), Arguments.of(true, 500),
                Arguments.of(false, 307), Arguments.of(true, 307));
    }

    @ParameterizedTest
    @MethodSource("customPathResponses")
    void dispatchesDeclaredCustomPathOverRealHttp(boolean async, int status) throws Exception {
        HttpServer downstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger customRequests = new AtomicInteger();
        AtomicInteger fallbackRequests = new AtomicInteger();
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> requestUri = new AtomicReference<>();
        AtomicReference<Headers> headers = new AtomicReference<>();
        downstream.createContext("/api/tasks", exchange -> {
            customRequests.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requestUri.set(exchange.getRequestURI().toString());
            headers.set(exchange.getRequestHeaders());
            byte[] bytes = (status == 200 ? "{\"status\":\"ok\",\"result\":\"done\"}" :
                    "{\"status\":\"error\",\"result\":\"provider down\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (status == 307) {
                exchange.getResponseHeaders().set("Location", "/redirect-target");
            }
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            } finally {
                exchange.close();
            }
        });
        downstream.createContext("/", exchange -> {
            fallbackRequests.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        downstream.start();
        try {
            String endpoint = "http://127.0.0.1:" + downstream.getAddress().getPort() + "/api/tasks?mode=custom";
            Agent agent = new Agent();
            agent.setName("SyntheticAgent");
            // Different origin: declaration controls the port; registration is never contacted.
            agent.setUrl("http://127.0.0.1:1/registration");
            agent.setStatus("HEALTHY");
            agent.setAgentCard(new AgentCard("SyntheticAgent", "test", null, "1.0", List.of(), Map.of(), List.of(endpoint)));
            AgentRepository agents = mock(AgentRepository.class);
            when(agents.findById(agent.getId())).thenReturn(Optional.of(agent));
            UUID taskId = UUID.randomUUID();
            TaskPersistenceService persistence = mock(TaskPersistenceService.class);
            when(persistence.createSubmittedTask(any(), any(), any())).thenReturn(new TaskService.TaskDto(
                    taskId, agent.getId(), agent.getName(), "ctx-http", "SUBMITTED", Map.of(), null, null,
                    ZonedDateTime.now(), ZonedDateTime.now()));
            when(persistence.updateTaskResult(any(), any(), any(), any())).thenAnswer(call ->
                    new TaskService.TaskDto(taskId, agent.getId(), agent.getName(), "ctx-http",
                            call.getArgument(1, TaskState.class).name(), Map.of(), call.getArgument(2),
                            call.getArgument(3), ZonedDateTime.now(), ZonedDateTime.now()));
            SecurityProperties properties = new SecurityProperties();
            properties.getSsrf().setAllowPrivateNetworks(true);
            SsrfValidator validator = new SsrfValidator(properties);
            AgentEventPublisher publisher = mock(AgentEventPublisher.class);
            AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
            RestClientConfig config = new RestClientConfig(validator);
            TaskService service = new TaskService(mock(TaskRepository.class), agents, persistence, validator,
                    publisher, config.restClientBuilder(), config.declaredTaskRestClient(), executor);

            var result = service.submitTask(new TaskService.SubmitTaskRequest(agent.getId(), "ctx-http", Map.of("query", "synthetic")), async);
            if (async) {
                assertThat(result.task().state()).isEqualTo("SUBMITTED");
                var worker = ArgumentCaptor.forClass(Runnable.class);
                verify(executor).execute(worker.capture());
                worker.getValue().run();
            } else {
                assertThat(result.task().state()).isEqualTo(status == 200 ? "COMPLETED" : "FAILED");
            }
            assertThat(customRequests.get()).isEqualTo(1);
            assertThat(fallbackRequests.get()).isZero();
            assertThat(requestUri.get()).isEqualTo("/api/tasks?mode=custom");
            assertThat(requestBody.get()).contains("\"query\":\"synthetic\"", "\"contextId\":\"ctx-http\"");
            assertThat(headers.get().getFirst("Authorization")).isNull();
            assertThat(headers.get().getFirst("X-API-Key")).isNull();
            assertThat(headers.get().getFirst("X-Task-Id")).isEqualTo(taskId.toString());
            verify(publisher).publishTaskUpdated(argThat(dto ->
                    dto.state().equals(status == 200 ? "COMPLETED" : "FAILED") &&
                            (status == 200 || dto.errorDetail().contains("provider down"))));
        } finally {
            downstream.stop(0);
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void connectionTimeSsrfRejectionFailsAdmittedTaskWithoutSendingHttp(boolean async, boolean declared) throws Exception {
        HttpServer downstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        downstream.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        downstream.start();
        try {
            Agent agent = new Agent();
            agent.setName("SyntheticAgent");
            agent.setUrl("http://127.0.0.1:" + downstream.getAddress().getPort());
            if (declared) {
                agent.setAgentCard(new AgentCard("SyntheticAgent", "test", null, "1.0", List.of(), Map.of(),
                        List.of(agent.getUrl() + "/api/tasks")));
            }
            AgentRepository agents = mock(AgentRepository.class);
            when(agents.findById(agent.getId())).thenReturn(Optional.of(agent));
            SecurityProperties properties = new SecurityProperties();
            properties.getSsrf().setAllowPrivateNetworks(true);
            SsrfValidator validator = new SsrfValidator(properties);
            UUID taskId = UUID.randomUUID();
            TaskPersistenceService persistence = mock(TaskPersistenceService.class);
            when(persistence.createSubmittedTask(any(), any(), any())).thenAnswer(call -> {
                // Admission passed. Tighten policy before connecting to exercise the real DNS resolver.
                properties.getSsrf().setAllowPrivateNetworks(false);
                return new TaskService.TaskDto(taskId, agent.getId(), agent.getName(), "ctx-http", "SUBMITTED",
                        Map.of(), null, null, ZonedDateTime.now(), ZonedDateTime.now());
            });
            when(persistence.updateTaskResult(any(), any(), any(), any())).thenAnswer(call ->
                    new TaskService.TaskDto(taskId, agent.getId(), agent.getName(), "ctx-http",
                            call.getArgument(1, TaskState.class).name(), Map.of(), call.getArgument(2),
                            call.getArgument(3), ZonedDateTime.now(), ZonedDateTime.now()));
            AgentEventPublisher publisher = mock(AgentEventPublisher.class);
            RestClientConfig config = new RestClientConfig(validator);
            AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
            TaskService service = new TaskService(mock(TaskRepository.class), agents, persistence, validator,
                    publisher, config.restClientBuilder(), config.declaredTaskRestClient(), executor);

            var result = service.submitTask(new TaskService.SubmitTaskRequest(agent.getId(), "ctx-http", Map.of("query", "test")), async);

            if (async) {
                assertThat(result.task().state()).isEqualTo("SUBMITTED");
                var worker = ArgumentCaptor.forClass(Runnable.class);
                verify(executor).execute(worker.capture());
                worker.getValue().run();
            } else {
                assertThat(result.task().state()).isEqualTo("FAILED");
                assertThat(result.task().errorDetail()).contains("restricted range");
            }
            assertThat(requests.get()).isZero();
            verify(persistence).updateTaskResult(eq(taskId), eq(TaskState.FAILED), any(), any());
            verify(publisher).publishTaskUpdated(argThat(dto ->
                    dto.state().equals("FAILED") && dto.errorDetail().contains("restricted range")));
        } finally {
            downstream.stop(0);
        }
    }

    static Stream<Arguments> downstreamErrors() {
        return Stream.of(
                Arguments.of(200, "{\"status\":\"error\",\"result\":\"protocol detail\",\"error\":\"legacy error\",\"message\":\"legacy message\"}", "protocol detail"),
                Arguments.of(200, "{\"status\":\"error\",\"error\":\"legacy error\",\"message\":\"legacy message\"}", "legacy error"),
                Arguments.of(200, "{\"status\":\"error\",\"message\":\"query must not exceed 500 characters\"}", "query must not exceed 500 characters"),
                Arguments.of(200, "{\"status\":\"error\",\"result\":null,\"error\":null,\"message\":\"provider unavailable\"}", "provider unavailable"),
                Arguments.of(200, "{\"status\":\"error\",\"message\":null}", "Downstream agent returned status: error"),
                Arguments.of(200, "{\"status\":\"error\"}", "Downstream agent returned status: error"),
                Arguments.of(400, "{\"status\":\"error\",\"message\":\"query must not exceed 500 characters\",\"code\":400}", "query must not exceed 500 characters"),
                Arguments.of(500, "{\"status\":\"error\",\"result\":\"provider unavailable\",\"contextId\":\"ctx-http\"}", "provider unavailable")
        );
    }

    @ParameterizedTest
    @MethodSource("downstreamErrors")
    void preservesErrorDetailsAcrossRealHttp(int status, String body, String detail) throws Exception {
        HttpServer downstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        downstream.createContext("/tasks", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            } finally {
                exchange.close();
            }
        });
        downstream.start();
        try {
            TaskPersistenceService persistence = mock(TaskPersistenceService.class);
            AgentEventPublisher publisher = mock(AgentEventPublisher.class);
            when(persistence.updateTaskResult(any(), any(), any(), any())).thenAnswer(invocation ->
                    new TaskService.TaskDto(invocation.getArgument(0), UUID.randomUUID(), "SyntheticAgent",
                            "ctx-http", invocation.getArgument(1, TaskState.class).name(), Map.of(),
                            invocation.getArgument(2), invocation.getArgument(3), ZonedDateTime.now(), ZonedDateTime.now()));
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(2_000);
            factory.setReadTimeout(2_000);
            TaskService service = new TaskService(mock(TaskRepository.class), mock(AgentRepository.class),
                    persistence, mock(SsrfValidator.class), publisher,
                    RestClient.builder().requestFactory(factory), mock(RestClient.class), new SimpleAsyncTaskExecutor());

            TaskService.TaskDto result = service.executeDownstreamProxy(UUID.randomUUID(), "ctx-http",
                    "SyntheticAgent", "http://127.0.0.1:" + downstream.getAddress().getPort(),
                    Map.of("query", "synthetic"), "corr-http", "trace-http");

            assertThat(result.state()).isEqualTo("FAILED");
            if (status == 200) {
                assertThat(result.errorDetail()).isEqualTo(detail);
                assertThat(result.response()).containsEntry("status", "error");
            } else {
                // Non-2xx already preserves the response body; do not replace this diagnostic.
                assertThat(result.errorDetail()).startsWith("Agent returned HTTP " + status)
                        .contains(body).contains(detail);
                assertThat(result.response()).isNull();
            }
            verify(publisher).publishTaskUpdated(result);
        } finally {
            downstream.stop(0);
        }
    }
}
