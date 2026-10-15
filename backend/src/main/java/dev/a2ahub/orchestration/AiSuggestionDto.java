package dev.a2ahub.orchestration;

import java.util.UUID;

public class AiSuggestionDto {
    private UUID agentId;
    private String agentName;
    private String reasoning;
    private String source;
    private boolean fallback;
    private double confidenceScore;
    private String fallbackReason;

    public AiSuggestionDto() {}

    public AiSuggestionDto(UUID agentId, String agentName, String reasoning) {
        this(agentId, agentName, reasoning, "LLM_REASONING", false, 0.95, null);
    }

    public AiSuggestionDto(UUID agentId, String agentName, String reasoning, String source, boolean fallback, double confidenceScore, String fallbackReason) {
        this.agentId = agentId;
        this.agentName = agentName;
        this.reasoning = reasoning;
        this.source = source != null ? source : "LLM_REASONING";
        this.fallback = fallback;
        this.confidenceScore = confidenceScore;
        this.fallbackReason = fallbackReason;
    }

    public static AiSuggestionDto fromLlm(UUID agentId, String agentName, String reasoning) {
        return new AiSuggestionDto(agentId, agentName, reasoning, "LLM_REASONING", false, 0.95, null);
    }

    public static AiSuggestionDto fromVectorFallback(UUID agentId, String agentName, double confidenceScore, String fallbackReason) {
        String reasoning = String.format(java.util.Locale.ROOT, "Selected via semantic vector similarity (confidence: %.2f) due to AI gateway fallback.", confidenceScore);
        return new AiSuggestionDto(agentId, agentName, reasoning, "VECTOR_FALLBACK", true, confidenceScore, fallbackReason);
    }

    public UUID getAgentId() { return agentId; }
    public void setAgentId(UUID agentId) { this.agentId = agentId; }

    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }

    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public boolean isFallback() { return fallback; }
    public void setFallback(boolean fallback) { this.fallback = fallback; }

    public double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(double confidenceScore) { this.confidenceScore = confidenceScore; }

    public String getFallbackReason() { return fallbackReason; }
    public void setFallbackReason(String fallbackReason) { this.fallbackReason = fallbackReason; }
}
