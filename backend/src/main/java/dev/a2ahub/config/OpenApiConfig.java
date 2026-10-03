package dev.a2ahub.config;

import dev.a2ahub.security.SecurityProperties;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String API_KEY_SCHEME_NAME = "ApiKeyAuth";

    @Value("${spring.application.version}")
    private String appVersion;

    @Bean
    public OpenAPI a2aHubOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("A2A-Hub API")
                        .description("Decentralized Agent Discovery Registry & Orchestration Hub for the Agent2Agent (A2A) Protocol")
                        .version(appVersion)
                        .license(new License().name("Apache 2.0").url("https://www.apache.org/licenses/LICENSE-2.0")))
                .externalDocs(new ExternalDocumentation()
                        .description("A2A Protocol Documentation")
                        .url("https://github.com/a2aproject/a2a-java"))
                .components(new Components().addSecuritySchemes(API_KEY_SCHEME_NAME, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-API-Key")));
    }

    @Bean
    // this customizer adds the API key security requirement to all endpoints that require authentication
    // /!! Note: this is equal to the logic in ApiKeyAuthFilter, so if you change one, you must change the other !!
    public OpenApiCustomizer apiKeySecurityCustomizer(SecurityProperties securityProperties) {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, pathItem) ->
                    pathItem.readOperationsMap().forEach((httpMethod, operation) -> {
                        boolean isWrite = httpMethod == PathItem.HttpMethod.POST
                                || httpMethod == PathItem.HttpMethod.PUT
                                || httpMethod == PathItem.HttpMethod.PATCH
                                || httpMethod == PathItem.HttpMethod.DELETE;

                        if (path.startsWith("/api/") && (isWrite || securityProperties.isRequireAuthForReads())) {
                            operation.addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME_NAME));
                        }
                    }));
        };
    }
}
