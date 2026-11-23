package dev.a2ahub.config;

import dev.a2ahub.security.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("WebConfig Unit Tests")
class WebConfigTest {

    @Test
    @DisplayName("Should configure CORS mappings for API and WS endpoints")
    void shouldConfigureCorsMappings() {
        SecurityProperties securityProperties = new SecurityProperties();
        securityProperties.setAllowedOrigins(List.of("http://localhost:5173", "https://hub.a2a.dev"));

        WebConfig webConfig = new WebConfig(securityProperties);
        var registry = new TestCorsRegistry();

        webConfig.addCorsMappings(registry);

        CorsConfiguration api = registry.apiConfiguration();
        assertThat(api.getAllowedOrigins()).containsExactly("http://localhost:5173", "https://hub.a2a.dev");
        assertThat(api.getExposedHeaders()).containsExactly("X-Correlation-Id", "traceparent");
    }

    @Test
    @DisplayName("Should apply security headers filter to response")
    void shouldApplySecurityHeaders() throws ServletException, IOException {
        SecurityProperties securityProperties = new SecurityProperties();
        WebConfig webConfig = new WebConfig(securityProperties);

        OncePerRequestFilter filter = webConfig.securityHeadersFilter();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/agents");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request, response, filterChain);

        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
        assertThat(response.getHeader("Strict-Transport-Security")).isEqualTo("max-age=63072000; includeSubDomains; preload");
        assertThat(response.getHeader("Permissions-Policy")).isEqualTo("camera=(), microphone=(), geolocation=(), payment=(), usb=(), display-capture=()");
        assertThat(response.getHeader("Cross-Origin-Opener-Policy")).isEqualTo("same-origin");
        assertThat(response.getHeader("Cross-Origin-Resource-Policy")).isEqualTo("same-origin");
        assertThat(response.getHeader("Content-Security-Policy")).contains("default-src 'none'");

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Should apply relaxed CSP for Swagger UI")
    void shouldApplyRelaxedCspForSwagger() throws ServletException, IOException {
        SecurityProperties securityProperties = new SecurityProperties();
        WebConfig webConfig = new WebConfig(securityProperties);

        OncePerRequestFilter filter = webConfig.securityHeadersFilter();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/swagger-ui/index.html");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request, response, filterChain);

        assertThat(response.getHeader("Content-Security-Policy")).contains("script-src 'self' 'unsafe-inline'");
        verify(filterChain).doFilter(request, response);
    }

    private static class TestCorsRegistry extends CorsRegistry {
        CorsConfiguration apiConfiguration() {
            return getCorsConfigurations().get("/api/**");
        }
    }
}
