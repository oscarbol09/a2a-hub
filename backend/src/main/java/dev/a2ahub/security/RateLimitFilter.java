package dev.a2ahub.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.RequestPath;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance, non-blocking Token Bucket Rate Limiting Filter.
 * Protects sensitive resource endpoints (/api/v1/agents, /api/v1/tasks)
 * against DoS, brute-force registration, and runaway automation.
 *
 * Defaults to 120 requests/minute per client IP.
 */
@Component
@Order(10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final List<String> RATE_LIMITED_PREFIXES = List.of(
            "/api/v1/agents",
            "/api/v1/tasks"
    );

    private static final long DEFAULT_CAPACITY = 120;
    private static final long DEFAULT_DURATION_SECONDS = 60;
    private static final long CLEANUP_INTERVAL_MS = 300_000; // 5 minutes
    private static final long IDLE_THRESHOLD_MS = 600_000; // 10 minutes

    private final long capacity;
    private final long refillDurationSeconds;
    private final double refillTokensPerMs;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong lastCleanupTime = new AtomicLong(System.currentTimeMillis());

    public RateLimitFilter() {
        this(DEFAULT_CAPACITY, DEFAULT_DURATION_SECONDS, new ObjectMapper());
    }

    @Autowired
    public RateLimitFilter(ObjectMapper objectMapper) {
        this(DEFAULT_CAPACITY, DEFAULT_DURATION_SECONDS, objectMapper);
    }

    public RateLimitFilter(long capacity, long refillDurationSeconds, ObjectMapper objectMapper) {
        this.capacity = capacity;
        this.refillDurationSeconds = refillDurationSeconds;
        this.refillTokensPerMs = (double) capacity / (refillDurationSeconds * 1000.0);
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        RequestPath requestPath;
        if (ServletRequestPathUtils.hasParsedRequestPath(request)) {
            requestPath = ServletRequestPathUtils.getParsedRequestPath(request);
        } else {
            requestPath = ServletRequestPathUtils.parseAndCache(request);
        }
        String rawPath = requestPath.pathWithinApplication().value();
        String path = "/" + rawPath.replaceAll("^/+", "");

        if (!isRateLimitedEndpoint(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        pruneStaleBucketsIfNecessary();

        String clientIp = extractClientIp(request);
        TokenBucket bucket = buckets.computeIfAbsent(clientIp, k -> new TokenBucket(capacity, refillTokensPerMs));

        if (!bucket.tryConsume()) {
            log.warn("Rate limit exceeded for client IP [{}] requesting [{}] [{}]. Allowed limit: {} req/{}s",
                    clientIp, request.getMethod(), path, capacity, refillDurationSeconds);

            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(refillDurationSeconds));
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Rate limit of " + capacity + " requests per " + refillDurationSeconds + "s exceeded. Please retry later."
            );
            problemDetail.setTitle("Too Many Requests");
            problemDetail.setType(URI.create("https://tools.ietf.org/html/rfc6585#section-4"));
            problemDetail.setProperty("retryAfterSeconds", refillDurationSeconds);

            objectMapper.writeValue(response.getWriter(), problemDetail);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isRateLimitedEndpoint(String path) {
        return RATE_LIMITED_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            int commaIdx = xForwardedFor.indexOf(',');
            if (commaIdx != -1) {
                return xForwardedFor.substring(0, commaIdx).trim();
            }
            return xForwardedFor.trim();
        }
        String remoteAddr = request.getRemoteAddr();
        return (remoteAddr != null && !remoteAddr.isBlank()) ? remoteAddr.trim() : "unknown";
    }

    private void pruneStaleBucketsIfNecessary() {
        long now = System.currentTimeMillis();
        long last = lastCleanupTime.get();
        if (now - last > CLEANUP_INTERVAL_MS && lastCleanupTime.compareAndSet(last, now)) {
            buckets.entrySet().removeIf(entry -> entry.getValue().isStale(now, IDLE_THRESHOLD_MS));
        }
    }

    /**
     * Non-blocking, thread-safe token bucket implementation per client IP.
     */
    static class TokenBucket {
        private final long capacity;
        private final double refillTokensPerMs;
        private double availableTokens;
        private long lastRefillTimestamp;

        TokenBucket(long capacity, double refillTokensPerMs) {
            this.capacity = capacity;
            this.refillTokensPerMs = refillTokensPerMs;
            this.availableTokens = capacity;
            this.lastRefillTimestamp = System.currentTimeMillis();
        }

        synchronized boolean tryConsume() {
            long now = System.currentTimeMillis();
            long elapsed = Math.max(0, now - lastRefillTimestamp);
            lastRefillTimestamp = now;
            availableTokens = Math.min(capacity, availableTokens + elapsed * refillTokensPerMs);

            if (availableTokens >= 1.0) {
                availableTokens -= 1.0;
                return true;
            }
            return false;
        }

        synchronized boolean isStale(long now, long idleThresholdMs) {
            return (now - lastRefillTimestamp) > idleThresholdMs && availableTokens >= capacity;
        }
    }
}
