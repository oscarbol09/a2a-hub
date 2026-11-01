package dev.a2ahub.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Converter
@Component
public class AesGcmAttributeConverter implements AttributeConverter<String, String> {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH_BYTES = 12; // 96-bit IV recommended for GCM
    private static final int GCM_TAG_LENGTH_BITS = 128; // 128-bit authentication tag

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AesGcmAttributeConverter.class);
    private static final String DEFAULT_DEV_KEY = "a2a-hub-default-master-encryption-key-32bytes!";

    private static volatile byte[] masterKeyBytes;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmAttributeConverter() {
        // Fallback default key if invoked by JPA before Spring context injection
        if (masterKeyBytes == null) {
            initKey(DEFAULT_DEV_KEY);
        }
    }

    @Autowired
    public void configure(SecurityProperties properties, @Autowired(required = false) org.springframework.core.env.Environment env) {
        String key = properties.getEncryptionKey();
        boolean isRestricted = isRestrictedEnvironment(env);
        boolean isKeyDefaultOrMissing = key == null || key.isBlank() || isDefaultDevKey(key);

        if (isKeyDefaultOrMissing) {
            if (isRestricted) {
                throw new IllegalStateException("A2A_HUB_ENCRYPTION_KEY environment variable must be configured with at least 16 characters for cryptographic safety in production environments.");
            }
            initKey(DEFAULT_DEV_KEY);
            log.warn("Running with default master encryption key in development/default mode. For production, set A2A_HUB_ENCRYPTION_KEY environment variable.");
        } else {
            initKey(key);
            log.info("AES-GCM master encryption key configured from application environment");
        }
    }

    public void configure(SecurityProperties properties) {
        configure(properties, null);
    }

    private static boolean isRestrictedEnvironment(org.springframework.core.env.Environment env) {
        if (env == null) {
            return false;
        }
        if (env.matchesProfiles("prod") || env.matchesProfiles("production")
                || env.matchesProfiles("staging") || env.matchesProfiles("uat") || env.matchesProfiles("live")) {
            return true;
        }
        for (String profile : env.getActiveProfiles()) {
            String lower = profile.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("prod") || lower.contains("stage") || lower.contains("staging")
                    || lower.contains("uat") || lower.contains("live") || lower.contains("release")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDefaultDevKey(String key) {
        if (key == null) {
            return false;
        }
        byte[] keyBytes = key.trim().getBytes(StandardCharsets.UTF_8);
        byte[] defaultBytes = DEFAULT_DEV_KEY.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(keyBytes, defaultBytes);
    }

    public static void initKey(String secret) {
        if (secret == null || secret.trim().length() < 16) {
            throw new IllegalArgumentException("Master encryption key must have at least 16 characters for cryptographic safety.");
        }
        // Entropy enforcement: reject trivial repetitive keys (must have at least 6 distinct characters)
        long distinctChars = secret.trim().chars().distinct().count();
        if (distinctChars < 6) {
            throw new IllegalArgumentException("Master encryption key has insufficient entropy (must contain at least 6 distinct characters).");
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            masterKeyBytes = sha.digest(secret.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize AES key", e);
        }
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.isBlank()) {
            return null;
        }

        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            SecretKey secretKey = new SecretKeySpec(masterKeyBytes, "AES");
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            byte[] cipherText = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

            // Pack IV + CipherText + Tag into single binary payload
            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + cipherText.length);
            byteBuffer.put(iv);
            byteBuffer.put(cipherText);

            return "ENC:" + Base64.getEncoder().encodeToString(byteBuffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM encryption failed for attribute", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }

        // Support backward compatibility for unencrypted legacy rows
        if (!dbData.startsWith("ENC:")) {
            return dbData;
        }

        try {
            String rawBase64 = dbData.substring(4);
            byte[] decoded = Base64.getDecoder().decode(rawBase64);

            int minPayloadBytes = GCM_IV_LENGTH_BYTES + (GCM_TAG_LENGTH_BITS / Byte.SIZE);
            if (decoded.length < minPayloadBytes) {
                throw new IllegalStateException("Decryption failed: payload is truncated or invalid");
            }

            ByteBuffer byteBuffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            byteBuffer.get(iv);

            byte[] cipherText = new byte[byteBuffer.remaining()];
            byteBuffer.get(cipherText);

            SecretKey secretKey = new SecretKeySpec(masterKeyBytes, "AES");
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);

            byte[] plainText = cipher.doFinal(cipherText);
            return new String(plainText, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM decryption failed for database payload", e);
        }
    }
}
