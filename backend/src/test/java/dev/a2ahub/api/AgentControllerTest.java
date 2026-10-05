package dev.a2ahub.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.a2ahub.agent.Agent;
import dev.a2ahub.agent.AgentNotFoundException;
import dev.a2ahub.agent.AgentRegistryService;
import dev.a2ahub.agent.RegisterAgentRequest;
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

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AgentController.class)
@Import({SecurityProperties.class, ApiKeyAuthFilter.class})
@DisplayName("AgentController Web Slice Tests")
class AgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AgentRegistryService agentRegistryService;

    @Test
    @DisplayName("POST /api/v1/agents - Should register new agent successfully")
    void shouldRegisterAgent() throws Exception {
        RegisterAgentRequest request = new RegisterAgentRequest("https://agent.test.io");
        Agent agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setName("Test Agent");
        agent.setUrl("https://agent.test.io");
        agent.setStatus("HEALTHY");

        when(agentRegistryService.register("https://agent.test.io")).thenReturn(agent);

        mockMvc.perform(post("/api/v1/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Test Agent"))
                .andExpect(jsonPath("$.url").value("https://agent.test.io"))
                .andExpect(jsonPath("$.status").value("HEALTHY"));

        verify(agentRegistryService).register("https://agent.test.io");
    }

    @Test
    @DisplayName("POST /api/v1/agents - Should reject invalid URLs (Validation Gate)")
    void shouldRejectInvalidUrlFormat() throws Exception {
        RegisterAgentRequest request = new RegisterAgentRequest("invalid-not-a-url");

        mockMvc.perform(post("/api/v1/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.details.url").exists());

        verify(agentRegistryService, never()).register(any());
    }

    @Test
    @DisplayName("GET /api/v1/agents - Should return paginated list of registered agents")
    void shouldReturnAllAgents() throws Exception {
        Agent agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setName("Alpha Agent");
        agent.setUrl("https://alpha.test.io");

        when(agentRegistryService.findAll(0, 50)).thenReturn(List.of(agent));

        mockMvc.perform(get("/api/v1/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Alpha Agent"))
                .andExpect(jsonPath("$[0].url").value("https://alpha.test.io"));

        verify(agentRegistryService).findAll(0, 50);
    }

    @Test
    @DisplayName("GET /api/v1/agents/{id} - Should never leak authTokenEnc in JSON payload (CWE-200)")
    void shouldNeverLeakAuthTokenEncInJsonResponse() throws Exception {
        UUID agentId = UUID.randomUUID();
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setName("SecureAgent");
        agent.setUrl("https://secure.agent.io");
        agent.setAuthType("BEARER");
        agent.setAuthTokenEnc("super-secret-token-that-must-not-leak");

        when(agentRegistryService.findById(agentId)).thenReturn(agent);

        mockMvc.perform(get("/api/v1/agents/{id}", agentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(agentId.toString()))
                .andExpect(jsonPath("$.name").value("SecureAgent"))
                .andExpect(jsonPath("$.authTokenEnc").doesNotExist())
                .andExpect(jsonPath("$.auth_token_enc").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/agents/{id} - Should return 404 ProblemDetail when agent not found")
    void shouldReturn404WhenAgentNotFound() throws Exception {
        UUID nonExistentId = UUID.randomUUID();
        when(agentRegistryService.findById(nonExistentId))
                .thenThrow(new AgentNotFoundException("Agent not found with ID: " + nonExistentId));

        mockMvc.perform(get("/api/v1/agents/{id}", nonExistentId))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value("Agent not found with ID: " + nonExistentId));
    }

    @Test
    @DisplayName("DELETE /api/v1/agents/{id} - Should return 204 No Content")
    void shouldUnregisterAgent() throws Exception {
        UUID id = UUID.randomUUID();
        doNothing().when(agentRegistryService).unregister(id);

        mockMvc.perform(delete("/api/v1/agents/{id}", id))
                .andExpect(status().isNoContent());

        verify(agentRegistryService).unregister(id);
    }
}
