package dev.a2ahub.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/** Initializes request tracing context and restores the caller's MDC after dispatch. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
public class TraceContextFilter extends OncePerRequestFilter {

    private static final String CORRELATION_ID = "correlationId";
    private static final String CONTEXT_ID = "contextId";
    private static final String TRACEPARENT = "traceparent";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        try {
            putOrGenerate(CORRELATION_ID, request.getHeader("X-Correlation-Id"), UUID.randomUUID().toString());
            putOrRemove(CONTEXT_ID, request.getHeader("X-Context-Id"));
            putOrGenerate(TRACEPARENT, request.getHeader("traceparent"), generateW3cTraceparent());
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
            if (previousContext != null) {
                MDC.setContextMap(previousContext);
            }
        }
    }

    private static void putOrGenerate(String key, String value, String generatedValue) {
        MDC.put(key, value == null || value.isBlank() ? generatedValue : value.trim());
    }

    private static void putOrRemove(String key, String value) {
        if (value == null || value.isBlank()) {
            MDC.remove(key);
        } else {
            MDC.put(key, value.trim());
        }
    }

    private static String generateW3cTraceparent() {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return "00-" + traceId + "-" + spanId + "-01";
    }
}
