package dev.a2ahub.agent;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.Setter;

import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "agents")
@Getter
@Setter
public class Agent {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false, unique = true, length = 512)
    private String url;

    private String version;

    @Column(name = "provider_name")
    private String providerName;

    @Column(nullable = false)
    private String status = "UNKNOWN";

    @Column(name = "auth_type")
    private String authType = "NONE";

    @Convert(converter = dev.a2ahub.security.AesGcmAttributeConverter.class)
    @Column(name = "auth_token_enc")
    private String authTokenEnc;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "agent_card", nullable = false)
    private AgentCard agentCard;

    @Column(name = "registered_at", insertable = false, updatable = false)
    private ZonedDateTime registeredAt;

    @Column(name = "last_seen_at")
    private ZonedDateTime lastSeenAt;
}
