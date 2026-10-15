package dev.a2ahub.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.a2ahub.orchestration.A2AOrchestrator;
import dev.a2ahub.orchestration.AiSuggestionDto;
import dev.a2ahub.orchestration.NoActiveAgentsException;
import dev.a2ahub.security.ApiKeyAuthFilter;
import dev.a2ahub.security.SecurityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrchestrationController.class)
@Import({SecurityProperties.class, ApiKeyAuthFilter.class})
@DisplayName("OrchestrationController Web Slice Tests")
class OrchestrationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private A2AOrchestrator orchestrator;

    @Test
    @DisplayName("POST /api/v1/orchestration/suggest - Should return 200 OK with suggestion DTO")
    void shouldReturnSuggestionSuccessfully() throws Exception {
        UUID agentId = UUID.randomUUID();
        AiSuggestionDto expectedDto = AiSuggestionDto.fromLlm(agentId, "WeatherAgent", "Matches forecast query");

        when(orchestrator.suggestAgent("Get current weather in Berlin"))
                .thenReturn(expectedDto);

        AiSuggestionRequest request = new AiSuggestionRequest("Get current weather in Berlin");

        mockMvc.perform(post("/api/v1/orchestration/suggest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(agentId.toString()))
                .andExpect(jsonPath("$.agentName").value("WeatherAgent"))
                .andExpect(jsonPath("$.source").value("LLM_REASONING"))
                .andExpect(jsonPath("$.fallback").value(false));
    }

    @Test
    @DisplayName("POST /api/v1/orchestration/suggest - Should return 200 OK with calibrated vector fallback score")
    void shouldReturnVectorFallbackWithCalibratedScore() throws Exception {
        UUID agentId = UUID.randomUUID();
        AiSuggestionDto expectedDto = AiSuggestionDto.fromVectorFallback(agentId, "WeatherAgent", 0.86, "GEMINI_API_KEY_UNCONFIGURED");

        when(orchestrator.suggestAgent("Check atmospheric pressure"))
                .thenReturn(expectedDto);

        AiSuggestionRequest request = new AiSuggestionRequest("Check atmospheric pressure");

        mockMvc.perform(post("/api/v1/orchestration/suggest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(agentId.toString()))
                .andExpect(jsonPath("$.agentName").value("WeatherAgent"))
                .andExpect(jsonPath("$.source").value("VECTOR_FALLBACK"))
                .andExpect(jsonPath("$.fallback").value(true))
                .andExpect(jsonPath("$.confidenceScore").value(0.86))
                .andExpect(jsonPath("$.fallbackReason").value("GEMINI_API_KEY_UNCONFIGURED"));
    }

    @Test
    @DisplayName("POST /api/v1/orchestration/suggest - Should return 400 Bad Request with ProblemDetail when input is blank")
    void shouldReturnBadRequestWhenInputIsBlank() throws Exception {
        AiSuggestionRequest request = new AiSuggestionRequest("   ");

        mockMvc.perform(post("/api/v1/orchestration/suggest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details.taskDescription").exists());
    }

    @Test
    @DisplayName("POST /api/v1/orchestration/suggest - Should return 400 Bad Request when input exceeds 1000 characters (DoS Guard)")
    void shouldReturnBadRequestWhenInputExceedsLimit() throws Exception {
        String oversizedDescription = "A".repeat(1001);
        AiSuggestionRequest request = new AiSuggestionRequest(oversizedDescription);

        mockMvc.perform(post("/api/v1/orchestration/suggest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details.taskDescription").exists());
    }

    @Test
    @DisplayName("POST /api/v1/orchestration/suggest - Should return 503 Service Unavailable when NoActiveAgentsException is thrown")
    void shouldReturn503WhenNoActiveAgentsAvailable() throws Exception {
        when(orchestrator.suggestAgent(anyString()))
                .thenThrow(new NoActiveAgentsException("No active agents found in the registry capable of servicing the requested task."));

        AiSuggestionRequest request = new AiSuggestionRequest("Analyze log traces");

        mockMvc.perform(post("/api/v1/orchestration/suggest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Orchestration Service Unavailable"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.type").value("urn:problem-type:no-active-agents"));
    }
}
