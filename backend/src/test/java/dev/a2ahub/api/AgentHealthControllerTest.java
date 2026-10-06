package dev.a2ahub.api;

import dev.a2ahub.health.AgentHealthMonitor;
import dev.a2ahub.health.AgentHealthService;
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

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentHealthController.class)
@Import({SecurityProperties.class, ApiKeyAuthFilter.class})
@DisplayName("AgentHealthController Web Slice Tests")
class AgentHealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgentHealthService agentHealthService;

    @Test
    @DisplayName("GET /api/v1/agents/{id}/health - Should return agent health history")
    void shouldReturnAgentHealthHistory() throws Exception {
        UUID agentId = UUID.randomUUID();
        AgentHealthService.HealthCheckItem logItem = new AgentHealthService.HealthCheckItem(1L, ZonedDateTime.now(), "HEALTHY", 120, null);
        AgentHealthService.AgentHealthResponse response = new AgentHealthService.AgentHealthResponse(
                agentId,
                "MetricsAgent",
                "HEALTHY",
                ZonedDateTime.now(),
                List.of(logItem)
        );

        when(agentHealthService.getAgentHealthHistory(agentId, 30)).thenReturn(response);

        mockMvc.perform(get("/api/v1/agents/{id}/health", agentId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(agentId.toString()))
                .andExpect(jsonPath("$.agentName").value("MetricsAgent"))
                .andExpect(jsonPath("$.currentStatus").value("HEALTHY"))
                .andExpect(jsonPath("$.history[0].status").value("HEALTHY"))
                .andExpect(jsonPath("$.history[0].latencyMs").value(120));

        verify(agentHealthService).getAgentHealthHistory(agentId, 30);
    }

    @Test
    @DisplayName("POST /api/v1/agents/{id}/health/check - Should trigger manual probe")
    void shouldTriggerManualProbe() throws Exception {
        UUID agentId = UUID.randomUUID();
        when(agentHealthService.triggerHealthCheck(agentId)).thenReturn(
                new AgentHealthMonitor.HealthCheckResult(agentId, "HEALTHY", 85, null)
        );

        mockMvc.perform(post("/api/v1/agents/{id}/health/check", agentId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HEALTHY"))
                .andExpect(jsonPath("$.latencyMs").value(85));

        verify(agentHealthService).triggerHealthCheck(agentId);
    }

    @Test
    @DisplayName("GET /api/v1/health/stats - Should return aggregate hub metrics")
    void shouldReturnHubHealthStats() throws Exception {
        AgentHealthService.HubHealthStats stats = new AgentHealthService.HubHealthStats(
                2,
                1,
                0,
                1,
                0,
                95.4
        );

        when(agentHealthService.getHubHealthStats()).thenReturn(stats);

        mockMvc.perform(get("/api/v1/health/stats")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAgents").value(2))
                .andExpect(jsonPath("$.healthyAgents").value(1))
                .andExpect(jsonPath("$.offlineAgents").value(1))
                .andExpect(jsonPath("$.averageLatencyMs").value(95.4));

        verify(agentHealthService).getHubHealthStats();
    }
}
