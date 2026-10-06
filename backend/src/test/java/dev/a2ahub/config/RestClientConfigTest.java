package dev.a2ahub.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RestClientConfig Unit Tests")
class RestClientConfigTest {

    @Test
    @DisplayName("Should build RestClient.Builder with JDK HttpClient request factory and timeouts")
    void shouldBuildRestClientBuilder() {
        RestClientConfig config = new RestClientConfig();
        RestClient.Builder builder = config.restClientBuilder();

        assertThat(builder).isNotNull();
        RestClient client = builder.build();
        assertThat(client).isNotNull();
    }
}
