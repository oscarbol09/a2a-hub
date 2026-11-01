package dev.a2ahub.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "a2ahub.security")
@Getter
@Setter
public class SecurityProperties {

    /**
     * Secret API key required for mutating endpoints (POST /api/v1/agents, DELETE /api/v1/agents/*).
     * If null or empty, authentication is disabled (suitable for local air-gapped demo).
     */
    private String apiKey;

    /**
     * Whether read endpoints (GET /api/v1/*) also require authentication.
     */
    private boolean requireAuthForReads = false;

    /**
     * Secret master key used for AES-GCM-256 encryption of downstream tokens in PostgreSQL.
     */
    private String encryptionKey = "a2a-hub-default-master-encryption-key-32bytes!";

    /**
     * Allowed CORS origins for browser web clients.
     */
    private List<String> allowedOrigins = List.of(
            "http://localhost:5173",
            "http://127.0.0.1:5173",
            "http://localhost:3000",
            "http://127.0.0.1:3000"
    );

    /**
     * Rate limiting and reverse proxy defense properties.
     */
    private RateLimit rateLimit = new RateLimit();

    /**
     * SSRF defense settings.
     */
    private Ssrf ssrf = new Ssrf();

    @Getter
    @Setter
    public static class RateLimit {
        private boolean enabled = true;
        private long capacity = 120;
        private long durationSeconds = 60;
        private int maxBuckets = 10_000;
        private List<String> trustedProxies = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class Ssrf {
        /**
         * Allow loopback and private subnets (ONLY for local tests / development).
         */
        private boolean allowPrivateNetworks = false;
    }
}
