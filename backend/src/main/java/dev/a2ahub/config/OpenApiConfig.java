package dev.a2ahub.config;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

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
                        .url("https://github.com/a2aproject/a2a-java"));
    }
}
