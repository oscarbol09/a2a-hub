package dev.a2ahub.task;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.a2ahub.agent.AgentRepository;
import dev.a2ahub.events.AgentEventPublisher;
import dev.a2ahub.security.SsrfValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskErrorDiagnosticsTest {

    private final TaskPersistenceService persistence = mock(TaskPersistenceService.class);
    private final RestClient.ResponseSpec response = mock(RestClient.ResponseSpec.class);
    private final TaskService service;

    TaskErrorDiagnosticsTest() {
        RestClient client = mock(RestClient.class);
        RestClient.Builder builder = mock(RestClient.Builder.class);
        RestClient.RequestBodyUriSpec uri = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec body = mock(RestClient.RequestBodySpec.class);
        when(builder.build()).thenReturn(client);
        when(client.post()).thenReturn(uri);
        when(uri.uri(any(URI.class))).thenReturn(body);
        when(body.contentType(MediaType.APPLICATION_JSON)).thenReturn(body);
        when(body.header(anyString(), anyString())).thenReturn(body);
        when(body.body(any(Object.class))).thenReturn(body);
        when(body.retrieve()).thenReturn(response);
        service = new TaskService(mock(TaskRepository.class), mock(AgentRepository.class), persistence,
                mock(SsrfValidator.class), mock(AgentEventPublisher.class), builder,
                mock(RestClient.class), new SimpleAsyncTaskExecutor());
    }

    static Stream<RuntimeException> oversizedFailures() {
        String detail = "x".repeat(4096) + "unbounded-tail-canary";
        return Stream.of(
                new RestClientResponseException("HTTP error", 500, "Server Error", null,
                        detail.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8),
                new ResourceAccessException(detail),
                new IllegalStateException(detail));
    }

    @ParameterizedTest
    @MethodSource("oversizedFailures")
    void shouldBoundExceptionDetailsBeforeLoggingAndPersistence(RuntimeException failure) {
        when(response.body(any(ParameterizedTypeReference.class))).thenThrow(failure);
        Logger logger = (Logger) LoggerFactory.getLogger(TaskService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String detail = executeAndCaptureError();
            assertThat(detail).hasSizeLessThanOrEqualTo(1024).endsWith("... [truncated]")
                    .doesNotContain("unbounded-tail-canary");
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getFormattedMessage())
                    .hasSizeLessThan(1300).doesNotContain("unbounded-tail-canary");
            assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void shouldBoundStructuredErrorsWithoutSplittingSurrogatePairs() {
        String detail = "\uD83D\uDE00".repeat(2048);
        when(response.body(any(ParameterizedTypeReference.class)))
                .thenReturn(Map.of("status", "error", "message", detail));

        String captured = executeAndCaptureError();

        assertThat(captured).hasSizeLessThanOrEqualTo(1024).endsWith("... [truncated]");
        String prefix = captured.substring(0, captured.indexOf("... [truncated]"));
        assertThat(prefix.length() % 2).isZero();
        assertThat(prefix).isEqualTo(detail.substring(0, prefix.length()));
    }

    @Test
    void shouldPreserveErrorDetailsAtTheLimit() {
        String detail = "x".repeat(1024);
        when(response.body(any(ParameterizedTypeReference.class)))
                .thenReturn(Map.of("status", "error", "result", detail));

        assertThat(executeAndCaptureError()).isEqualTo(detail);
    }

    private String executeAndCaptureError() {
        UUID taskId = UUID.randomUUID();
        service.executeDownstreamProxy(taskId, "ctx", "TestAgent", "https://agent.example",
                Map.of("input", "test"), "corr", "trace");
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(persistence).updateTaskResult(eq(taskId), eq(TaskState.FAILED), any(), error.capture());
        return error.getValue();
    }
}
