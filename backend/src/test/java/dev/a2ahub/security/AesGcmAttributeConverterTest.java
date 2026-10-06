package dev.a2ahub.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AesGcmAttributeConverter Encryption Unit Tests")
class AesGcmAttributeConverterTest {

    private AesGcmAttributeConverter converter;

    @BeforeEach
    void setUp() {
        converter = new AesGcmAttributeConverter();
        SecurityProperties properties = new SecurityProperties();
        properties.setEncryptionKey("test-secret-encryption-key-32-chars-long!");
        converter.configure(properties);
    }

    @Test
    @DisplayName("Should encrypt and decrypt token symmetrically with AES-GCM-256")
    void shouldEncryptAndDecryptSuccessfully() {
        String sensitiveToken = "bearer_secret_token_live_xyz_987654";

        String dbCipher = converter.convertToDatabaseColumn(sensitiveToken);

        assertThat(dbCipher).isNotNull();
        assertThat(dbCipher).startsWith("ENC:");
        assertThat(dbCipher).isNotEqualTo(sensitiveToken);

        String decrypted = converter.convertToEntityAttribute(dbCipher);
        assertThat(decrypted).isEqualTo(sensitiveToken);
    }

    @Test
    @DisplayName("Should return null when converting null attributes")
    void shouldHandleNullGracefully() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    @DisplayName("Should support backward compatibility for unencrypted legacy tokens")
    void shouldSupportLegacyPlaintext() {
        String plaintext = "legacy_token_123";
        String decrypted = converter.convertToEntityAttribute(plaintext);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("Should reject encryption keys with fewer than 16 characters")
    void shouldRejectShortKey() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AesGcmAttributeConverter.initKey("too-short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 16 characters");
    }

    @Test
    @DisplayName("Should fail fast in production profile if encryption key is default or missing")
    void shouldFailFastInProductionWithDefaultKey() {
        org.springframework.mock.env.MockEnvironment mockEnv = new org.springframework.mock.env.MockEnvironment();
        mockEnv.setActiveProfiles("prod");

        SecurityProperties defaultProps = new SecurityProperties();
        defaultProps.setEncryptionKey("a2a-hub-default-master-encryption-key-32bytes!");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> converter.configure(defaultProps, mockEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Default development master encryption key cannot be used in production");

        SecurityProperties emptyProps = new SecurityProperties();
        emptyProps.setEncryptionKey("");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> converter.configure(emptyProps, mockEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("A2A_HUB_ENCRYPTION_KEY environment variable must be set");
    }
}
