package dev.a2ahub.config;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
            assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("corr-123");
            assertThat(response.getHeader("traceparent"))
                    .isEqualTo("00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01");
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
            assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(MDC.get("correlationId"));
            assertThat(response.getHeader("traceparent")).isEqualTo(MDC.get("traceparent"));
            assertThat(MDC.get("contextId")).isNull();
        });

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
    @ParameterizedTest
    @ValueSource(strings = {"bad\r\nforged", "bad\tvalue", "bad value", "non-ascii-\u00e9", "", "a/b"})
    void shouldRejectUnsafeIdentifiers(String value) throws ServletException, IOException {
        assertUnsafeIdentifiersAreReplaced(value);
    }

    @Test
    void shouldRejectOversizedIdentifiers() throws ServletException, IOException {
        assertUnsafeIdentifiersAreReplaced("a".repeat(129));
    }

    @Test
    void shouldPreserveIdentifiersAtTheLengthLimit() throws ServletException, IOException {
        String value = "a".repeat(123) + "._:-9";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", value);
        request.addHeader("X-Context-Id", value);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TraceContextFilter().doFilter(request, response, (req, res) -> {
            assertThat(MDC.get("correlationId")).isEqualTo(value);
            assertThat(MDC.get("contextId")).isEqualTo(value);
            assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(value);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "invalid",
            "00-00000000000000000000000000000000-bbbbbbbbbbbbbbbb-01",
            "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-0000000000000000-01",
            "00-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA-bbbbbbbbbbbbbbbb-01",
            "ff-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01",
            "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01-extra",
            "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01\r\nforged"
    })
    void shouldReplaceMalformedTraceparents(String value) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent", value);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TraceContextFilter().doFilter(request, response, (req, res) -> {
            assertThat(MDC.get("traceparent")).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
            assertThat(response.getHeader("traceparent")).isEqualTo(MDC.get("traceparent"));
            assertThat(response.getHeader("traceparent")).isNotEqualTo(value);
        });
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    private void assertUnsafeIdentifiersAreReplaced(String value) throws ServletException, IOException {
        MDC.put("contextId", "outer-context");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", value);
        request.addHeader("X-Context-Id", value);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TraceContextFilter().doFilter(request, response, (req, res) -> {
            assertThat(MDC.get("correlationId")).matches("[0-9a-f-]{36}");
            assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(MDC.get("correlationId"));
            assertThat(MDC.get("contextId")).isNull();
        });
        assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of("contextId", "outer-context"));
    }

}
