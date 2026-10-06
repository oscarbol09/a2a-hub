package dev.a2ahub.config;

import dev.a2ahub.security.SecurityProperties;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OpenApiConfig Unit Tests")
class OpenApiConfigTest {

    @Test
    @DisplayName("Should build OpenAPI documentation model with security schemes")
    void shouldBuildOpenApiDocumentationModel() {
        OpenApiConfig config = new OpenApiConfig();
        ReflectionTestUtils.setField(config, "appVersion", "1.0.0");

        OpenAPI openAPI = config.a2aHubOpenAPI();

        assertThat(openAPI.getInfo().getTitle()).isEqualTo("A2A-Hub API");
        assertThat(openAPI.getInfo().getVersion()).isEqualTo("1.0.0");
        assertThat(openAPI.getComponents().getSecuritySchemes()).containsKey("ApiKeyAuth");
    }

    @Test
    @DisplayName("Should customize security requirements on OpenAPI paths according to write operations")
    void shouldCustomizeSecurityRequirementsOnPaths() {
        OpenApiConfig config = new OpenApiConfig();
        SecurityProperties securityProperties = new SecurityProperties();
        securityProperties.setRequireAuthForReads(false);

        OpenApiCustomizer customizer = config.apiKeySecurityCustomizer(securityProperties);

        OpenAPI openAPI = new OpenAPI();
        Paths paths = new Paths();

        PathItem agentsPath = new PathItem();
        Operation getOp = new Operation();
        Operation postOp = new Operation();
        agentsPath.setGet(getOp);
        agentsPath.setPost(postOp);

        paths.addPathItem("/api/v1/agents", agentsPath);
        openAPI.setPaths(paths);

        customizer.customise(openAPI);

        // GET operation should NOT have security requirement because requireAuthForReads is false
        assertThat(getOp.getSecurity()).isNull();

        // POST operation SHOULD have ApiKeyAuth requirement
        assertThat(postOp.getSecurity()).hasSize(1);
        assertThat(postOp.getSecurity().get(0)).containsKey("ApiKeyAuth");
    }

    @Test
    @DisplayName("Should customize security requirements on read operations when requireAuthForReads is true")
    void shouldCustomizeSecurityOnReadOperationsWhenEnabled() {
        OpenApiConfig config = new OpenApiConfig();
        SecurityProperties securityProperties = new SecurityProperties();
        securityProperties.setRequireAuthForReads(true);

        OpenApiCustomizer customizer = config.apiKeySecurityCustomizer(securityProperties);

        OpenAPI openAPI = new OpenAPI();
        Paths paths = new Paths();

        PathItem agentsPath = new PathItem();
        Operation getOp = new Operation();
        agentsPath.setGet(getOp);

        paths.addPathItem("/api/v1/agents", agentsPath);
        openAPI.setPaths(paths);

        customizer.customise(openAPI);

        assertThat(getOp.getSecurity()).hasSize(1);
        assertThat(getOp.getSecurity().get(0)).containsKey("ApiKeyAuth");
    }

    @Test
    @DisplayName("Should handle empty paths gracefully in OpenApiCustomizer")
    void shouldHandleNullPathsGracefully() {
        OpenApiConfig config = new OpenApiConfig();
        SecurityProperties securityProperties = new SecurityProperties();
        OpenApiCustomizer customizer = config.apiKeySecurityCustomizer(securityProperties);

        OpenAPI openAPI = new OpenAPI();
        openAPI.setPaths(null);

        // Must not throw NullPointerException
        customizer.customise(openAPI);
    }
}
