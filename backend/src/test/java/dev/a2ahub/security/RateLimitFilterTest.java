package dev.a2ahub.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("RateLimitFilter Security & Token Bucket Unit Tests")
class RateLimitFilterTest {

    private ObjectMapper objectMapper;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        filterChain = mock(FilterChain.class);
    }

    @Test
    @DisplayName("Should allow requests within configured token bucket limit")
    void shouldAllowRequestsWithinLimit() throws ServletException, IOException {
        RateLimitFilter filter = new RateLimitFilter(5, 60, objectMapper);

        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/agents");
            request.setRemoteAddr("192.168.1.100");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        }

        verify(filterChain, times(5)).doFilter(any(), any());
    }

    @Test
    @DisplayName("Should block request exceeding token bucket limit with HTTP 429 and RFC 7807 ProblemDetail")
    void shouldBlockRequestExceedingLimit() throws ServletException, IOException {
        RateLimitFilter filter = new RateLimitFilter(3, 60, objectMapper);

        // Exhaust 3 tokens
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks");
            request.setRemoteAddr("10.0.0.50");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, filterChain);
            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        }

        // 4th request should be rejected with 429
        MockHttpServletRequest rejectedRequest = new MockHttpServletRequest("POST", "/api/v1/tasks");
        rejectedRequest.setRemoteAddr("10.0.0.50");
        MockHttpServletResponse rejectedResponse = new MockHttpServletResponse();

        filter.doFilterInternal(rejectedRequest, rejectedResponse, filterChain);

        assertThat(rejectedResponse.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(rejectedResponse.getHeader("Retry-After")).isEqualTo("60");
        assertThat(rejectedResponse.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        JsonNode json = objectMapper.readTree(rejectedResponse.getContentAsString());
        assertThat(json.get("title").asText()).isEqualTo("Too Many Requests");
        assertThat(json.get("status").asInt()).isEqualTo(429);
        assertThat(json.get("detail").asText()).contains("Rate limit");

        verify(filterChain, times(3)).doFilter(any(), any());
    }

    @Test
    @DisplayName("Should isolate rate limit buckets by client IP address")
    void shouldIsolateBucketsByClientIp() throws ServletException, IOException {
        RateLimitFilter filter = new RateLimitFilter(2, 60, objectMapper);

        // Client A exhausts quota
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest reqA = new MockHttpServletRequest("GET", "/api/v1/agents");
            reqA.setRemoteAddr("192.168.1.1");
            MockHttpServletResponse resA = new MockHttpServletResponse();
            filter.doFilterInternal(reqA, resA, filterChain);
            assertThat(resA.getStatus()).isEqualTo(HttpStatus.OK.value());
        }

        // Client A's 3rd request is blocked
        MockHttpServletRequest reqA3 = new MockHttpServletRequest("GET", "/api/v1/agents");
        reqA3.setRemoteAddr("192.168.1.1");
        MockHttpServletResponse resA3 = new MockHttpServletResponse();
        filter.doFilterInternal(reqA3, resA3, filterChain);
        assertThat(resA3.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

        // Client B should still be allowed
        MockHttpServletRequest reqB = new MockHttpServletRequest("GET", "/api/v1/agents");
        reqB.setRemoteAddr("192.168.1.2");
        MockHttpServletResponse resB = new MockHttpServletResponse();
        filter.doFilterInternal(reqB, resB, filterChain);
        assertThat(resB.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    @DisplayName("Should parse client IP from X-Forwarded-For header when behind trusted reverse proxy")
    void shouldParseClientIpFromXForwardedFor() throws ServletException, IOException {
        RateLimitFilter filter = new RateLimitFilter(1, 60, objectMapper);

        MockHttpServletRequest req1 = new MockHttpServletRequest("POST", "/api/v1/tasks");
        req1.addHeader("X-Forwarded-For", "203.0.113.195, 70.41.3.18, 150.172.238.178");
        req1.setRemoteAddr("10.0.0.1"); // Load balancer IP (site-local trusted)
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        filter.doFilterInternal(req1, res1, filterChain);
        assertThat(res1.getStatus()).isEqualTo(HttpStatus.OK.value());

        // Second request with same client IP in X-Forwarded-For should be blocked
        MockHttpServletRequest req2 = new MockHttpServletRequest("POST", "/api/v1/tasks");
        req2.addHeader("X-Forwarded-For", "203.0.113.195, 70.41.3.18");
        req2.setRemoteAddr("10.0.0.2"); // Different load balancer IP
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilterInternal(req2, res2, filterChain);
        assertThat(res2.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    @DisplayName("Should ignore X-Forwarded-For from untrusted public IP to prevent IP spoofing")
    void shouldIgnoreXForwardedForFromUntrustedIp() throws ServletException, IOException {
        RateLimitFilter filter = new RateLimitFilter(1, 60, objectMapper);

        // Attacker with public IP 198.51.100.25 attempts to bypass rate limits by randomizing X-Forwarded-For
        MockHttpServletRequest req1 = new MockHttpServletRequest("POST", "/api/v1/tasks");
        req1.setRemoteAddr("198.51.100.25");
        req1.addHeader("X-Forwarded-For", "1.1.1.1");
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        filter.doFilterInternal(req1, res1, filterChain);
        assertThat(res1.getStatus()).isEqualTo(HttpStatus.OK.value());

        // Second request with different spoofed X-Forwarded-For from same public IP must be blocked
        MockHttpServletRequest req2 = new MockHttpServletRequest("POST", "/api/v1/tasks");
        req2.setRemoteAddr("198.51.100.25");
        req2.addHeader("X-Forwarded-For", "2.2.2.2");
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilterInternal(req2, res2, filterChain);
        assertThat(res2.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    @DisplayName("Should trust configured custom reverse proxy CIDRs")
    void shouldTrustConfiguredProxyCidr() throws ServletException, IOException {
        SecurityProperties props = new SecurityProperties();
        props.getRateLimit().setTrustedProxies(List.of("198.51.100.0/24"));

        RateLimitFilter filter = new RateLimitFilter(1, 60, 10_000, objectMapper, props);

        MockHttpServletRequest req1 = new MockHttpServletRequest("POST", "/api/v1/tasks");
        req1.setRemoteAddr("198.51.100.55"); // In configured trusted CIDR
        req1.addHeader("X-Forwarded-For", "203.0.113.10");
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        filter.doFilterInternal(req1, res1, filterChain);
        assertThat(res1.getStatus()).isEqualTo(HttpStatus.OK.value());

        // Second request for client 203.0.113.10 through proxy should be rate limited
        MockHttpServletRequest req2 = new MockHttpServletRequest("POST", "/api/v1/tasks");
        req2.setRemoteAddr("198.51.100.55");
        req2.addHeader("X-Forwarded-For", "203.0.113.10");
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilterInternal(req2, res2, filterChain);
        assertThat(res2.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    @DisplayName("Should bound bucket cache size to prevent memory exhaustion (DoS)")
    void shouldBoundBucketCacheSize() throws ServletException, IOException {
        int maxCapacity = 50;
        RateLimitFilter filter = new RateLimitFilter(10, 60, maxCapacity, objectMapper, null);

        // Flood with 200 distinct private IPs
        for (int i = 0; i < 200; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/agents");
            req.setRemoteAddr("10.0." + (i / 256) + "." + (i % 256));
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilterInternal(req, res, filterChain);
        }

        // Cache size must never exceed configured maximum bound
        assertThat(filter.getBucketCount()).isLessThanOrEqualTo(maxCapacity);
    }

    @Test
    @DisplayName("ApiKeyAuthFilter should have higher precedence than RateLimitFilter (@Order(10) < @Order(20))")
    void shouldHaveCorrectFilterOrderPrecedence() {
        Order apiKeyOrder = AnnotationUtils.findAnnotation(ApiKeyAuthFilter.class, Order.class);
        Order rateLimitOrder = AnnotationUtils.findAnnotation(RateLimitFilter.class, Order.class);

        assertThat(apiKeyOrder).isNotNull();
        assertThat(rateLimitOrder).isNotNull();
        assertThat(apiKeyOrder.value()).isEqualTo(10);
        assertThat(rateLimitOrder.value()).isEqualTo(20);
        assertThat(apiKeyOrder.value()).isLessThan(rateLimitOrder.value());
    }

    @Test
    @DisplayName("Should not apply rate limiting to non-target endpoints")
    void shouldNotRateLimitNonTargetEndpoints() throws ServletException, IOException {
        RateLimitFilter filter = new RateLimitFilter(1, 60, objectMapper);

        // Access discovery endpoint multiple times - should not be limited
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/discovery");
            request.setRemoteAddr("10.0.0.5");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, filterChain);
            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        }

        // Access Swagger UI - should not be limited
        MockHttpServletRequest swaggerReq = new MockHttpServletRequest("GET", "/swagger-ui/index.html");
        swaggerReq.setRemoteAddr("10.0.0.5");
        MockHttpServletResponse swaggerRes = new MockHttpServletResponse();
        filter.doFilterInternal(swaggerReq, swaggerRes, filterChain);
        assertThat(swaggerRes.getStatus()).isEqualTo(HttpStatus.OK.value());

        verify(filterChain, times(6)).doFilter(any(), any());
    }

    @Test
    @DisplayName("Should rate limit subpaths of /api/v1/agents and /api/v1/tasks")
    void shouldRateLimitSubpaths() throws ServletException, IOException {
        RateLimitFilter filter = new RateLimitFilter(2, 60, objectMapper);

        MockHttpServletRequest req1 = new MockHttpServletRequest("GET", "/api/v1/agents/00000000-0000-0000-0000-000000000001");
        req1.setRemoteAddr("192.168.1.50");
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        filter.doFilterInternal(req1, res1, filterChain);
        assertThat(res1.getStatus()).isEqualTo(HttpStatus.OK.value());

        MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/api/v1/tasks/00000000-0000-0000-0000-000000000002/status");
        req2.setRemoteAddr("192.168.1.50");
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilterInternal(req2, res2, filterChain);
        assertThat(res2.getStatus()).isEqualTo(HttpStatus.OK.value());

        MockHttpServletRequest req3 = new MockHttpServletRequest("GET", "/api/v1/tasks/00000000-0000-0000-0000-000000000002");
        req3.setRemoteAddr("192.168.1.50");
        MockHttpServletResponse res3 = new MockHttpServletResponse();
        filter.doFilterInternal(req3, res3, filterChain);
        assertThat(res3.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }
}
