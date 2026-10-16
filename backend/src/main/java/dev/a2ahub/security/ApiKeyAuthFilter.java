package dev.a2ahub.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.server.RequestPath;
import org.springframework.web.util.ServletRequestPathUtils;

@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private static final List<String> SWAGGER_ENDPOINTS = List.of(
            "/v3/api-docs",
            "/swagger-ui"
    );
    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    private final SecurityProperties securityProperties;
    private final Environment env;

    @Autowired
    public ApiKeyAuthFilter(SecurityProperties securityProperties, @Autowired(required = false) Environment env) {
        this.securityProperties = securityProperties;
        this.env = env;
    }

    public ApiKeyAuthFilter(SecurityProperties securityProperties) {
        this(securityProperties, null);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String configuredKey = securityProperties.getApiKey();

        // If no API key is configured, auth is disabled (local/open mode)
        if (configuredKey == null || configuredKey.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        // Canonicalize path using Spring MVC's engine to prevent //, /../, and matrix variable bypasses
        RequestPath requestPath;
        if (ServletRequestPathUtils.hasParsedRequestPath(request)) {
            requestPath = ServletRequestPathUtils.getParsedRequestPath(request);
        } else {
            requestPath = ServletRequestPathUtils.parseAndCache(request);
        }
        String rawPath = requestPath.pathWithinApplication().value();
        String path = "/" + rawPath.replaceAll("^/+", "");
        String method = request.getMethod();

        // Check if route requires auth
        boolean isProtected = isProtectedEndpoint(path, method);

        if (!isProtected) {
            filterChain.doFilter(request, response);
            return;
        }

        // Validate provided credentials with constant-time comparison (defends against timing attacks CWE-208)
        String providedKey = extractApiKey(request);

        if (providedKey == null || !java.security.MessageDigest.isEqual(
                providedKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                configuredKey.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Invalid or missing API key\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isProtectedEndpoint(String path, String method) {
        if (isSwaggerEndpoint(path)) {
            return !isDevOrTest();
        }

        if (isOutsideApi(path) || isOpenRead(method)) {
            return false;
        }

        return true;
    }

    private boolean isSwaggerEndpoint(String path) {
        return SWAGGER_ENDPOINTS.stream().anyMatch(path::startsWith);
    }

    private boolean isDevOrTest() {
        return env != null && (env.matchesProfiles("dev") || env.matchesProfiles("test"));
    }

    private boolean isOutsideApi(String path) {
        return !path.startsWith("/api/");
    }

    private boolean isOpenRead(String method) {
        return !securityProperties.isRequireAuthForReads() && !isWriteMethod(method);
    }

    private boolean isWriteMethod(String method) {
        return MUTATING_METHODS.contains(method.toUpperCase(Locale.ROOT));
    }

    private String extractApiKey(HttpServletRequest request) {
        String key = request.getHeader(API_KEY_HEADER);
        if (key != null && !key.isBlank()) {
            return key.trim();
        }

        String authHeader = request.getHeader(AUTH_HEADER);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length()).trim();
        }

        return null;
    }
}
