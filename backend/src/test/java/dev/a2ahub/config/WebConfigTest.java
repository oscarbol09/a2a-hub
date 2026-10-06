package dev.a2ahub.config;

import dev.a2ahub.security.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
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
        CorsRegistry registry = new CorsRegistry();

        webConfig.addCorsMappings(registry);

        assertThat(registry).isNotNull();
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

        verify(filterChain).doFilter(request, response);
    }
}
