package dev.a2ahub.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SsrfValidator Security Unit Tests")
class SsrfValidatorTest {

    private SecurityProperties securityProperties;
    private SsrfValidator ssrfValidator;

    @BeforeEach
    void setUp() {
        securityProperties = new SecurityProperties();
        ssrfValidator = new SsrfValidator(securityProperties);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1:8080",
            "http://localhost:18080",
            "http://127.0.0.1:5432",
            "http://169.254.169.254/latest/meta-data/",
            "http://10.0.0.5:8080",
            "http://192.168.1.1:80",
            "http://172.16.0.1",
            "http://0.0.0.0:8080",
            "http://[::1]:8080",
            "http://[::ffff:127.0.0.1]:8080",
            "http://[::ffff:169.254.169.254]:8080",
            "http://[fc00::1]:8080"
    })
    @DisplayName("Should block loopback, private ranges, link-local, cloud metadata, and IPv4-mapped IPv6 (CWE-918)")
    void shouldBlockSsrfTargets(String forbiddenUrl) {
        assertThatThrownBy(() -> ssrfValidator.validateSafeRemoteUrl(forbiddenUrl))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://example.com/agent",
            "file:///etc/passwd",
            "gopher://127.0.0.1:6379/_",
            "ldap://127.0.0.1:389/o=anon"
    })
    @DisplayName("Should block non-HTTP/HTTPS schemes")
    void shouldBlockUnsupportedSchemes(String url) {
        assertThatThrownBy(() -> ssrfValidator.validateSafeRemoteUrl(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported scheme");
    }

    @Test
    @DisplayName("Should allow private networks when explicitly configured (allowPrivateNetworks=true)")
    void shouldAllowPrivateWhenConfigured() {
        securityProperties.getSsrf().setAllowPrivateNetworks(true);

        assertThatCode(() -> ssrfValidator.validateSafeRemoteUrl("http://127.0.0.1:8080"))
                .doesNotThrowAnyException();
    }
}
