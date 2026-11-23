package dev.a2ahub.config;

import dev.a2ahub.security.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final SecurityProperties securityProperties;

    public WebConfig(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = securityProperties.getAllowedOrigins().toArray(new String[0]);

        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders(TraceContextFilter.CORRELATION_ID_HEADER, TraceContextFilter.TRACEPARENT_HEADER)
                .maxAge(3600);

        registry.addMapping("/ws/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "OPTIONS");
    }

    @Bean
    public OncePerRequestFilter securityHeadersFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain filterChain) throws ServletException, IOException {
                String path = request.getRequestURI();

                // 1. Content Security Policy (segregated for Swagger UI vs REST API/WebSocket)
                if (path != null && (path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs"))) {
                    response.setHeader("Content-Security-Policy",
                            "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                            + "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
                } else {
                    response.setHeader("Content-Security-Policy",
                            "default-src 'none'; frame-ancestors 'none'; connect-src 'self' ws: wss:; base-uri 'none'; form-action 'none'");
                }

                // 2. MIME & Clickjacking protection
                response.setHeader("X-Content-Type-Options", "nosniff");
                response.setHeader("X-Frame-Options", "DENY");

                // 3. Modern transport & privacy headers
                response.setHeader("Strict-Transport-Security", "max-age=63072000; includeSubDomains; preload");
                response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
                response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=(), display-capture=()");

                // 4. Cross-Origin isolation (defends against Spectre microarchitectural XS-Leaks)
                response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
                response.setHeader("Cross-Origin-Resource-Policy", "same-origin");

                filterChain.doFilter(request, response);
            }
        };
    }
}
