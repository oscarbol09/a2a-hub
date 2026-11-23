package dev.a2ahub.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

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
        ZonedDateTime lastSeenAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double similarityScore
) {
    public static AgentResponse fromEntity(Agent agent) {
        return fromEntity(agent, null);
    }

    public static AgentResponse fromEntity(Agent agent, Double similarityScore) {
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
                agent.getLastSeenAt(),
                similarityScore
        );
    }
}
