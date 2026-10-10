package dev.a2ahub.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.a2ahub.agent.Agent;
import dev.a2ahub.agent.AgentDiscoveryService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class A2AOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(A2AOrchestrator.class);
    private final AgentDiscoveryService agentDiscoveryService;
    private final ChatLanguageModel chatModel;
    private final ObjectMapper objectMapper;

    public A2AOrchestrator(
            AgentDiscoveryService agentDiscoveryService,
            ObjectMapper objectMapper,
            @Value("${a2ahub.gemini.api-key:${GEMINI_API_KEY:}}") String apiKey) {
        this.agentDiscoveryService = agentDiscoveryService;
        this.objectMapper = objectMapper;
        
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("Gemini API key is missing. Please set GEMINI_API_KEY.");
        }
        
        this.chatModel = GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gemini-3.8-flash")
                .build();
    }

    public AiSuggestionDto suggestAgent(String taskDescription) {
        List<Agent> candidates = agentDiscoveryService.discoverSemantic(taskDescription, 5);
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalStateException("No candidate agents found for the given task.");
        }

        String agentsInfo = candidates.stream()
                .map(a -> String.format("ID: %s, Name: %s, Description: %s", a.getId(), a.getName(), a.getDescription()))
                .collect(Collectors.joining("\n"));

        String prompt = String.format(
            "Given the following task description, choose the best agent from the candidates provided.\n" +
            "Task Description: %s\n\n" +
            "Candidates:\n%s\n\n" +
            "Output MUST be exactly valid JSON, without any markdown formatting, containing the fields: " +
            "\"agentId\" (uuid string), \"agentName\" (string), and \"reasoning\" (string).",
            taskDescription, agentsInfo
        );

        String response = chatModel.generate(prompt);
        return parseResponse(response);
    }

    private AiSuggestionDto parseResponse(String response) {
        try {
            String json = response.trim();
            if (json.startsWith("```json")) {
                json = json.substring(7);
            } else if (json.startsWith("```")) {
                json = json.substring(3);
            }
            if (json.endsWith("```")) {
                json = json.substring(0, json.length() - 3);
            }
            json = json.trim();
            
            return objectMapper.readValue(json, AiSuggestionDto.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse JSON from LLM: {}", response, e);
            throw new RuntimeException("Failed to parse LLM response into AiSuggestionDto", e);
        }
    }
}
