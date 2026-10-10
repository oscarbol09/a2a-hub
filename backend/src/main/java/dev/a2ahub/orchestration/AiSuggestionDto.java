package dev.a2ahub.orchestration;

import java.util.UUID;

public class AiSuggestionDto {
    private UUID agentId;
    private String agentName;
    private String reasoning;

    public AiSuggestionDto() {}

    public AiSuggestionDto(UUID agentId, String agentName, String reasoning) {
        this.agentId = agentId;
        this.agentName = agentName;
        this.reasoning = reasoning;
    }

    public UUID getAgentId() { return agentId; }
    public void setAgentId(UUID agentId) { this.agentId = agentId; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }
}
