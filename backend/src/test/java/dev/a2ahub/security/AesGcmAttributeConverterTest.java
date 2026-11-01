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
    @DisplayName("Should fail fast outside dev/test if encryption key is default or missing")
    void shouldFailFastOutsideDevOrTestWithDefaultKey() {
        for (String profile : new String[]{"prod", "production", "staging", "uat"}) {
            org.springframework.mock.env.MockEnvironment restrictedEnv = new org.springframework.mock.env.MockEnvironment();
            restrictedEnv.setActiveProfiles(profile);

            SecurityProperties defaultProps = new SecurityProperties();
            defaultProps.setEncryptionKey("a2a-hub-default-master-encryption-key-32bytes!");

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> converter.configure(defaultProps, restrictedEnv))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("A2A_HUB_ENCRYPTION_KEY");

            SecurityProperties emptyProps = new SecurityProperties();
            emptyProps.setEncryptionKey("");

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> converter.configure(emptyProps, restrictedEnv))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("A2A_HUB_ENCRYPTION_KEY");
        }

        // Environment with no dev or test profile (e.g. empty or default profile) safely falls back
        org.springframework.mock.env.MockEnvironment defaultEnv = new org.springframework.mock.env.MockEnvironment();
        SecurityProperties defaultProps = new SecurityProperties();
        defaultProps.setEncryptionKey("a2a-hub-default-master-encryption-key-32bytes!");

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> converter.configure(defaultProps, defaultEnv));

        SecurityProperties emptyProps = new SecurityProperties();
        emptyProps.setEncryptionKey("");
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> converter.configure(emptyProps, defaultEnv));
    }

    @Test
    @DisplayName("Should allow default encryption key in dev and test profiles")
    void shouldAllowDefaultKeyInDevAndTestProfiles() {
        for (String profile : new String[]{"dev", "test"}) {
            org.springframework.mock.env.MockEnvironment safeEnv = new org.springframework.mock.env.MockEnvironment();
            safeEnv.setActiveProfiles(profile);

            SecurityProperties defaultProps = new SecurityProperties();
            defaultProps.setEncryptionKey("a2a-hub-default-master-encryption-key-32bytes!");

            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> converter.configure(defaultProps, safeEnv));

            SecurityProperties emptyProps = new SecurityProperties();
            emptyProps.setEncryptionKey("");

            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> converter.configure(emptyProps, safeEnv));
        }
    }

    @Test
    @DisplayName("Should allow custom encryption key in any profile including prod")
    void shouldAllowCustomKeyInProduction() {
        org.springframework.mock.env.MockEnvironment prodEnv = new org.springframework.mock.env.MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        SecurityProperties customProps = new SecurityProperties();
        customProps.setEncryptionKey("super-secure-production-key-32-bytes!");

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> converter.configure(customProps, prodEnv));
    }

    @Test
    @DisplayName("Should allow default key when environment is null for backward compatibility")
    void shouldAllowDefaultKeyWhenEnvIsNull() {
        SecurityProperties defaultProps = new SecurityProperties();
        defaultProps.setEncryptionKey("a2a-hub-default-master-encryption-key-32bytes!");

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> converter.configure(defaultProps, null));
    }
}
