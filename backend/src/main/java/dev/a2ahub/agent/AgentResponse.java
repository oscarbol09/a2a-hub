package dev.a2ahub.agent;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Immutable API response DTO for Agent details, decoupling database entity from HTTP contract.
 */
public record AgentResponse(
        UUID id,
        String name,
        String description,
        String url,
        String version,
        String providerName,
        String status,
        String authType,
        AgentCard agentCard,
        ZonedDateTime registeredAt,
        ZonedDateTime lastSeenAt
) {
    public static AgentResponse fromEntity(Agent agent) {
        if (agent == null) {
            return null;
        }
        return new AgentResponse(
                agent.getId(),
                agent.getName(),
                agent.getDescription(),
                agent.getUrl(),
                agent.getVersion(),
                agent.getProviderName(),
                agent.getStatus(),
                agent.getAuthType(),
                agent.getAgentCard(),
                agent.getRegisteredAt(),
                agent.getLastSeenAt()
        );
    }
}
