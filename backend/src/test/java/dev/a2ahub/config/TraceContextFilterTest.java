package dev.a2ahub.config;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
@DisplayName("Trace context filter tests")
class TraceContextFilterTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("Should expose incoming trace headers to the request chain and restore MDC")
    void shouldPropagateIncomingHeadersAndRestoreMdc() throws ServletException, IOException {
        MDC.put("unrelated", "outer");
        TraceContextFilter filter = new TraceContextFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks");
        request.addHeader("X-Correlation-Id", "corr-123");
        request.addHeader("X-Context-Id", "ctx-456");
        request.addHeader("traceparent", "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            assertThat(MDC.getCopyOfContextMap()).containsAllEntriesOf(Map.of(
                    "correlationId", "corr-123",
                    "contextId", "ctx-456",
                    "traceparent", "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01"));
        });

        assertThat(MDC.getCopyOfContextMap()).containsEntry("unrelated", "outer").hasSize(1);
    }

    @Test
    @DisplayName("Should generate correlation and W3C trace identifiers when headers are absent")
    void shouldGenerateMissingIdentifiers() throws ServletException, IOException {
        TraceContextFilter filter = new TraceContextFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/agents");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            assertThat(MDC.get("correlationId")).isNotBlank();
            assertThat(MDC.get("traceparent"))
                    .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
            assertThat(MDC.get("contextId")).isNull();
        });

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
}
