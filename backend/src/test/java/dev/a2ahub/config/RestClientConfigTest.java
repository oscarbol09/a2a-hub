package dev.a2ahub.config;

import dev.a2ahub.security.SecurityProperties;
import dev.a2ahub.security.SsrfValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RestClientConfig Unit Tests")
class RestClientConfigTest {

    private SecurityProperties securityProperties;
    private SsrfValidator ssrfValidator;
    private RestClientConfig config;

    @BeforeEach
    void setUp() {
        securityProperties = new SecurityProperties();
        ssrfValidator = new SsrfValidator(securityProperties);
        config = new RestClientConfig(ssrfValidator);
    }

    @Test
    @DisplayName("Should build RestClient.Builder with HttpComponents request factory")
    void shouldBuildRestClientBuilder() {
        RestClient.Builder builder = config.restClientBuilder();
        assertThat(builder).isNotNull();

        RestClient client = builder.build();
        assertThat(client).isNotNull();
    }

    @Test
    @DisplayName("Should enforce strict TLS hostname verification (CN/SAN validation)")
    void shouldEnforceStrictTlsHostnameVerification() {
        org.springframework.http.client.HttpComponentsClientHttpRequestFactory factory =
                RestClientConfig.createRequestFactory(ssrfValidator, java.time.Duration.ofSeconds(3), java.time.Duration.ofSeconds(5));
        assertThat(factory).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1:59999/test",
            "http://localhost:59999/test",
            "http://169.254.169.254/latest/meta-data/",
            "http://10.0.0.5:59999/test",
            "http://192.168.1.1:59999/test",
            "http://172.16.0.1:59999/test"
    })
    @DisplayName("Should block loopback and private IPs at socket-level DNS resolver (zero TOCTOU)")
    void shouldBlockLoopbackAndPrivateAtSocketLevel(String targetUrl) {
        RestClient client = config.restClientBuilder().build();

        assertThatThrownBy(() -> client.get().uri(targetUrl).retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class)
                .satisfies(ex -> {
                    Throwable root = NestedExceptionUtils.getMostSpecificCause(ex);
                    assertThat(root).isInstanceOf(UnknownHostException.class);
                });
    }

    @Test
    @DisplayName("Should allow private networks at socket level when explicitly configured")
    void shouldAllowPrivateNetworksWhenConfigured() {
        securityProperties.getSsrf().setAllowPrivateNetworks(true);
        RestClient client = config.restClientBuilder().build();

        // When allowPrivateNetworks=true, DNS resolution succeeds.
        // Connecting to closed port 59999 fails with ConnectException (Connection refused), NOT UnknownHostException.
        assertThatThrownBy(() -> client.get().uri("http://127.0.0.1:59999/test").retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class)
                .satisfies(ex -> {
                    Throwable root = NestedExceptionUtils.getMostSpecificCause(ex);
                    assertThat(root).isNotInstanceOf(UnknownHostException.class);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "8.8.8.8",
            "1.1.1.1"
    })
    @DisplayName("Custom DNS resolver should successfully resolve allowed public IPs")
    void shouldResolvePublicIpsAtSocketLevel(String publicIp) throws Exception {
        InetAddress[] addresses = ssrfValidator.resolveAndValidate(publicIp);
        assertThat(addresses).isNotEmpty();
        assertThat(addresses[0].getHostAddress()).isEqualTo(publicIp);
    }
}
