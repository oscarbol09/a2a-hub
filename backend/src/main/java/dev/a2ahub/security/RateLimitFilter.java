package dev.a2ahub.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;

/**
 * High-performance, non-blocking Token Bucket Rate Limiting Filter with bounded LRU memory.
 * Protects sensitive resource endpoints (/api/v1/agents, /api/v1/tasks)
 * against DoS, brute-force registration, and runaway automation.
 *
 * Enforces client IP authenticity by strictly rejecting spoofed X-Forwarded-For
 * headers from untrusted clients, and defends against memory exhaustion attacks
 * via bounded Caffeine cache eviction.
 *
 * Ordered after ApiKeyAuthFilter (@Order(10)) so unauthenticated requests
 * are rejected before allocating rate limiter tokens.
 */
@Component
@Order(20)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final List<String> RATE_LIMITED_PREFIXES = List.of(
            "/api/v1/agents",
            "/api/v1/tasks"
    );

    private static final long DEFAULT_CAPACITY = 120;
    private static final long DEFAULT_DURATION_SECONDS = 60;
    private static final int DEFAULT_MAX_BUCKETS = 10_000;
    private static final long IDLE_EXPIRATION_MINUTES = 10;

    private final long capacity;
    private final long refillDurationSeconds;
    private final double refillTokensPerMs;
    private final ObjectMapper objectMapper;
    private final SecurityProperties securityProperties;
    private final Cache<String, TokenBucket> buckets;

    public RateLimitFilter() {
        this(DEFAULT_CAPACITY, DEFAULT_DURATION_SECONDS, DEFAULT_MAX_BUCKETS, new ObjectMapper(), null);
    }

    @Autowired
    public RateLimitFilter(ObjectMapper objectMapper, @Autowired(required = false) SecurityProperties securityProperties) {
        this(
                securityProperties != null && securityProperties.getRateLimit() != null
                        ? securityProperties.getRateLimit().getCapacity() : DEFAULT_CAPACITY,
                securityProperties != null && securityProperties.getRateLimit() != null
                        ? securityProperties.getRateLimit().getDurationSeconds() : DEFAULT_DURATION_SECONDS,
                securityProperties != null && securityProperties.getRateLimit() != null
                        ? securityProperties.getRateLimit().getMaxBuckets() : DEFAULT_MAX_BUCKETS,
                objectMapper,
                securityProperties
        );
    }

    public RateLimitFilter(long capacity, long refillDurationSeconds, ObjectMapper objectMapper) {
        this(capacity, refillDurationSeconds, DEFAULT_MAX_BUCKETS, objectMapper, null);
    }

    public RateLimitFilter(long capacity, long refillDurationSeconds, int maxBuckets, ObjectMapper objectMapper, SecurityProperties securityProperties) {
        this.capacity = capacity > 0 ? capacity : DEFAULT_CAPACITY;
        this.refillDurationSeconds = refillDurationSeconds > 0 ? refillDurationSeconds : DEFAULT_DURATION_SECONDS;
        this.refillTokensPerMs = (double) this.capacity / (this.refillDurationSeconds * 1000.0);
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.securityProperties = securityProperties;

        int limit = maxBuckets > 0 ? maxBuckets : DEFAULT_MAX_BUCKETS;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(limit)
                .expireAfterAccess(Duration.ofMinutes(IDLE_EXPIRATION_MINUTES))
                .build();
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

        String clientIp = extractClientIp(request);
        TokenBucket bucket = buckets.get(clientIp, k -> new TokenBucket(capacity, refillTokensPerMs));

        if (bucket == null || !bucket.tryConsume()) {
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

    String extractClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        String cleanRemoteAddr = (remoteAddr != null && !remoteAddr.isBlank()) ? remoteAddr.trim() : "unknown";

        if (!isTrustedProxy(cleanRemoteAddr)) {
            return cleanRemoteAddr;
        }

        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            int commaIdx = xForwardedFor.indexOf(',');
            String clientIp = (commaIdx != -1) ? xForwardedFor.substring(0, commaIdx).trim() : xForwardedFor.trim();
            if (isValidIp(clientIp)) {
                return clientIp;
            }
        }

        return cleanRemoteAddr;
    }

    private boolean isTrustedProxy(String ip) {
        if ("unknown".equalsIgnoreCase(ip) || ip.isBlank()) {
            return false;
        }

        if (securityProperties != null && securityProperties.getRateLimit() != null) {
            List<String> configured = securityProperties.getRateLimit().getTrustedProxies();
            if (configured != null) {
                for (String proxyPattern : configured) {
                    if (matchesTrustedProxy(ip, proxyPattern)) {
                        return true;
                    }
                }
            }
        }

        try {
            InetAddress addr = InetAddress.getByName(ip);
            return addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private boolean matchesTrustedProxy(String ip, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        String trimmed = pattern.trim();
        if (trimmed.equalsIgnoreCase(ip)) {
            return true;
        }
        if (trimmed.contains("/")) {
            try {
                String[] parts = trimmed.split("/");
                InetAddress target = InetAddress.getByName(ip);
                InetAddress network = InetAddress.getByName(parts[0]);
                int prefixLength = Integer.parseInt(parts[1]);
                byte[] targetBytes = target.getAddress();
                byte[] networkBytes = network.getAddress();
                if (targetBytes.length != networkBytes.length) {
                    return false;
                }
                int fullBytes = prefixLength / 8;
                int remainingBits = prefixLength % 8;
                for (int i = 0; i < fullBytes; i++) {
                    if (targetBytes[i] != networkBytes[i]) {
                        return false;
                    }
                }
                if (remainingBits > 0) {
                    int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                    if ((targetBytes[fullBytes] & mask) != (networkBytes[fullBytes] & mask)) {
                        return false;
                    }
                }
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    private static boolean isValidIp(String ip) {
        if (ip == null || ip.isBlank() || ip.length() > 45) {
            return false;
        }
        for (int i = 0; i < ip.length(); i++) {
            char c = ip.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == '.' || c == ':')) {
                return false;
            }
        }
        try {
            InetAddress addr = InetAddress.getByName(ip);
            return addr.getHostAddress().equalsIgnoreCase(ip) || ip.contains(":");
        } catch (UnknownHostException e) {
            return false;
        }
    }

    public long getBucketCount() {
        this.buckets.cleanUp();
        return this.buckets.estimatedSize();
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
    }
}
