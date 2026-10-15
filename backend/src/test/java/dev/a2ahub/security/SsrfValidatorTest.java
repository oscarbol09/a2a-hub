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

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1",
            "localhost",
            "localhost.localdomain",
            "169.254.169.254",
            "10.0.0.5",
            "192.168.1.1",
            "172.16.0.1",
            "0.0.0.0",
            "::1",
            "[::1]",
            "[::ffff:127.0.0.1]",
            "[fc00::1]"
    })
    @DisplayName("resolveAndValidate should throw UnknownHostException for forbidden IPs and loopback hosts")
    void shouldThrowUnknownHostExceptionForForbiddenHosts(String forbiddenHost) {
        assertThatThrownBy(() -> ssrfValidator.resolveAndValidate(forbiddenHost))
                .isInstanceOf(java.net.UnknownHostException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "8.8.8.8",
            "1.1.1.1"
    })
    @DisplayName("resolveAndValidate should resolve allowed public IPs successfully")
    void shouldResolvePublicIps(String publicIp) throws Exception {
        java.net.InetAddress[] addresses = ssrfValidator.resolveAndValidate(publicIp);

        org.assertj.core.api.Assertions.assertThat(addresses).isNotEmpty();
        org.assertj.core.api.Assertions.assertThat(addresses[0].getHostAddress()).isEqualTo(publicIp);
    }

    @Test
    @DisplayName("resolveAndValidate should allow private networks when configured")
    void shouldAllowPrivateNetworksInResolveAndValidateWhenConfigured() throws Exception {
        securityProperties.getSsrf().setAllowPrivateNetworks(true);

        java.net.InetAddress[] addresses = ssrfValidator.resolveAndValidate("127.0.0.1");

        org.assertj.core.api.Assertions.assertThat(addresses).isNotEmpty();
        org.assertj.core.api.Assertions.assertThat(addresses[0].getHostAddress()).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("resolveAndValidate should reject null or empty host")
    void shouldRejectEmptyHost() {
        assertThatThrownBy(() -> ssrfValidator.resolveAndValidate(null))
                .isInstanceOf(java.net.UnknownHostException.class);

        assertThatThrownBy(() -> ssrfValidator.resolveAndValidate("   "))
                .isInstanceOf(java.net.UnknownHostException.class);
    }
}
