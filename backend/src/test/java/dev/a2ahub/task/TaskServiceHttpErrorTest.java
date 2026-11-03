package dev.a2ahub.task;

import com.sun.net.httpserver.HttpServer;
import dev.a2ahub.agent.AgentRepository;
import dev.a2ahub.events.AgentEventPublisher;
import dev.a2ahub.security.SsrfValidator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskServiceHttpErrorTest {

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
                    RestClient.builder().requestFactory(factory), new SimpleAsyncTaskExecutor());

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
