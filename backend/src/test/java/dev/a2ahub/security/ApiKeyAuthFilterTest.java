package dev.a2ahub.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("ApiKeyAuthFilter Security Unit Tests")
class ApiKeyAuthFilterTest {

    private SecurityProperties securityProperties;
    private ApiKeyAuthFilter filter;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        securityProperties = new SecurityProperties();
        filter = new ApiKeyAuthFilter(securityProperties);
        filterChain = mock(FilterChain.class);
    }

    @Test
    @DisplayName("Should allow all requests when API key is not configured (open/dev mode)")
    void shouldAllowWhenNoKeyConfigured() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/agents");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Should block unauthenticated mutating POST request when API key is configured (CWE-306)")
    void shouldBlockUnauthenticatedPostWhenKeyConfigured() throws ServletException, IOException {
        securityProperties.setApiKey("secret-token-123");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/agents");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Unauthorized");
    }

    @Test
    @DisplayName("Should allow mutating POST request with valid X-API-Key header")
    void shouldAllowWithValidXApiKey() throws ServletException, IOException {
        securityProperties.setApiKey("secret-token-123");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/agents");
        request.addHeader("X-API-Key", "secret-token-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Should allow mutating POST request with valid Authorization Bearer header")
    void shouldAllowWithValidBearerToken() throws ServletException, IOException {
        securityProperties.setApiKey("secret-token-123");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/agents");
        request.addHeader("Authorization", "Bearer secret-token-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Should allow unauthenticated GET read requests by default when requireAuthForReads is false")
    void shouldAllowPublicReadsByDefault() throws ServletException, IOException {
        securityProperties.setApiKey("secret-token-123");
        securityProperties.setRequireAuthForReads(false);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/agents");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Should block unauthenticated POST /api/v1/tasks when API key is configured")
    void shouldBlockUnauthenticatedTaskSubmission() throws ServletException, IOException {
        securityProperties.setApiKey("secret-token-123");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @ParameterizedTest(name = "GET {0} in profile {1} (requireAuthForReads={2}) should bypass auth")
    @CsvSource({
            "/v3/api-docs,                dev,  true",
            "/v3/api-docs/swagger-config, dev,  true",
            "/swagger-ui.html,            dev,  true",
            "/swagger-ui/index.html,      dev,  true",
            "/v3/api-docs,                test, false",
            "/swagger-ui/index.html,      test, false"
    })
    @DisplayName("Should let OpenAPI docs and Swagger UI bypass auth when in dev or test profiles")
    void shouldBypassAuthForApiDocsInDevOrTest(String path, String profile, boolean requireAuthForReads) throws ServletException, IOException {
        // given
        org.springframework.mock.env.MockEnvironment mockEnv = new org.springframework.mock.env.MockEnvironment();
        mockEnv.setActiveProfiles(profile);
        ApiKeyAuthFilter envFilter = new ApiKeyAuthFilter(securityProperties, mockEnv);

        securityProperties.setApiKey("secret-token-123");
        securityProperties.setRequireAuthForReads(requireAuthForReads);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        envFilter.doFilterInternal(request, response, filterChain);

        // then
        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @ParameterizedTest(name = "GET {0} in prod profile without API key should be blocked")
    @CsvSource({
            "/v3/api-docs",
            "/v3/api-docs/swagger-config",
            "/swagger-ui.html",
            "/swagger-ui/index.html"
    })
    @DisplayName("Should block unauthenticated OpenAPI docs and Swagger UI in non-dev/test environments")
    void shouldBlockUnauthenticatedApiDocsInProduction(String path) throws ServletException, IOException {
        // given
        org.springframework.mock.env.MockEnvironment prodEnv = new org.springframework.mock.env.MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        ApiKeyAuthFilter prodFilter = new ApiKeyAuthFilter(securityProperties, prodEnv);

        securityProperties.setApiKey("secret-token-123");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        prodFilter.doFilterInternal(request, response, filterChain);

        // then
        verify(filterChain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Unauthorized");
    }

    @ParameterizedTest(name = "GET {0} in prod profile with valid API key should be allowed")
    @CsvSource({
            "/v3/api-docs",
            "/swagger-ui/index.html"
    })
    @DisplayName("Should allow authenticated OpenAPI docs and Swagger UI in non-dev/test environments")
    void shouldAllowAuthenticatedApiDocsInProduction(String path) throws ServletException, IOException {
        // given
        org.springframework.mock.env.MockEnvironment prodEnv = new org.springframework.mock.env.MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        ApiKeyAuthFilter prodFilter = new ApiKeyAuthFilter(securityProperties, prodEnv);

        securityProperties.setApiKey("secret-token-123");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader("X-API-Key", "secret-token-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        prodFilter.doFilterInternal(request, response, filterChain);

        // then
        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @ParameterizedTest(name = "GET {0} in prod profile when apiKey is not configured should be allowed")
    @CsvSource({
            "/v3/api-docs",
            "/swagger-ui/index.html"
    })
    @DisplayName("Should allow OpenAPI docs and Swagger UI when apiKey is not configured regardless of profile")
    void shouldAllowApiDocsWhenApiKeyNotConfigured(String path) throws ServletException, IOException {
        // given
        org.springframework.mock.env.MockEnvironment prodEnv = new org.springframework.mock.env.MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        ApiKeyAuthFilter prodFilter = new ApiKeyAuthFilter(securityProperties, prodEnv);

        securityProperties.setApiKey(null);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        prodFilter.doFilterInternal(request, response, filterChain);

        // then
        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Should block unauthenticated GET when requireAuthForReads is true")
    void shouldBlockReadsWhenRequireAuthForReads() throws ServletException, IOException {
        securityProperties.setApiKey("secret-token-123");
        securityProperties.setRequireAuthForReads(true);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/agents");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Should allow GET with valid API key when requireAuthForReads is true")
    void shouldAllowReadsWithValidKeyWhenRequireAuthForReads() throws ServletException, IOException {
        securityProperties.setApiKey("secret-token-123");
        securityProperties.setRequireAuthForReads(true);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/agents");
        request.addHeader("X-API-Key", "secret-token-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Should block request with invalid API key")
    void shouldBlockInvalidApiKey() throws ServletException, IOException {
        securityProperties.setApiKey("secret-token-123");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/agents");
        request.addHeader("X-API-Key", "wrong-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Should block unauthenticated mutating request attempting path traversal or multi-slash evasion")
    void shouldBlockPathTraversalEvasion() throws ServletException, IOException {
        securityProperties.setApiKey("secret-token-123");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "//api/v1/agents");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(401);
    }
}
