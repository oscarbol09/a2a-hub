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
import java.util.regex.Pattern;

/** Initializes request tracing context and restores the caller's MDC after dispatch. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
public class TraceContextFilter extends OncePerRequestFilter {

    static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    static final String TRACEPARENT_HEADER = "traceparent";

    private static final String CORRELATION_ID = "correlationId";
    private static final String CONTEXT_ID = "contextId";
    private static final String TRACEPARENT = "traceparent";
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern W3C_TRACEPARENT = Pattern.compile(
            "00-(?!0{32}-)[0-9a-f]{32}-(?!0{16}-)[0-9a-f]{16}-[0-9a-f]{2}");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        try {
            putOrGenerate(CORRELATION_ID, request.getHeader(CORRELATION_ID_HEADER), UUID.randomUUID().toString());
            putOrRemove(CONTEXT_ID, request.getHeader("X-Context-Id"));
            String traceparent = request.getHeader(TRACEPARENT_HEADER);
            MDC.put(TRACEPARENT, traceparent != null && W3C_TRACEPARENT.matcher(traceparent).matches()
                    ? traceparent : generateW3cTraceparent());
            response.setHeader(CORRELATION_ID_HEADER, MDC.get(CORRELATION_ID));
            response.setHeader(TRACEPARENT_HEADER, MDC.get(TRACEPARENT));
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
            if (previousContext != null) {
                MDC.setContextMap(previousContext);
            }
        }
    }

    private static void putOrGenerate(String key, String value, String generatedValue) {
        MDC.put(key, isSafeIdentifier(value) ? value : generatedValue);
    }

    private static void putOrRemove(String key, String value) {
        if (!isSafeIdentifier(value)) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    private static boolean isSafeIdentifier(String value) {
        return value != null && SAFE_IDENTIFIER.matcher(value).matches();
    }

    private static String generateW3cTraceparent() {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return "00-" + traceId + "-" + spanId + "-01";
    }
}
