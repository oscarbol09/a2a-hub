package dev.a2ahub.health;

import dev.a2ahub.agent.Agent;
import dev.a2ahub.agent.AgentNotFoundException;
import dev.a2ahub.agent.AgentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentHealthService Unit Tests")
class AgentHealthServiceTest {

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private HealthCheckRepository healthCheckRepository;

    @Mock
    private AgentHealthMonitor healthMonitor;

    @Spy
    private HealthProperties healthProperties = new HealthProperties();

    @InjectMocks
    private AgentHealthService healthService;

    @ParameterizedTest(name = "Requested limit {0} should clamp to PageRequest size {1}")
    @CsvSource({
            "-10, 1",
            "0, 1",
            "1, 1",
            "50, 50",
            "100, 100",
            "150, 100",
            "1000, 100"
    })
    @DisplayName("Should clamp health history limit strictly within range [1, 100]")
    void shouldClampHealthHistoryLimit(int requestedLimit, int expectedFetchLimit) {
        UUID agentId = UUID.randomUUID();
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setName("TestAgent");
        agent.setStatus("HEALTHY");

        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(healthCheckRepository.findRecentByAgentId(eq(agentId), any(PageRequest.class)))
                .thenReturn(List.of());
        healthProperties.setRetentionHours(72);

        AgentHealthService.AgentHealthResponse response = healthService.getAgentHealthHistory(agentId, requestedLimit);

        assertThat(response).isNotNull();
        assertThat(response.agentId()).isEqualTo(agentId);
        assertThat(response.retentionHours()).isEqualTo(72);

        ArgumentCaptor<PageRequest> pageRequestCaptor = ArgumentCaptor.forClass(PageRequest.class);
        verify(healthCheckRepository).findRecentByAgentId(eq(agentId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(expectedFetchLimit);
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should throw AgentNotFoundException when agent does not exist")
    void shouldThrowWhenAgentNotFound() {
        UUID agentId = UUID.randomUUID();
        when(agentRepository.findById(agentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> healthService.getAgentHealthHistory(agentId, 10))
                .isInstanceOf(AgentNotFoundException.class)
                .hasMessage("Agent not found with ID: " + agentId);
    }

    @Test
    @DisplayName("Should calculate total, healthy, degraded, offline, and unknown stats with rounded latency")
    void shouldCalculateHubHealthStatsWithLatencyRounding() {
        List<AgentRepository.StatusCountProjection> projections = List.of(
                createStatusProjection("HEALTHY", 10L),
                createStatusProjection("DEGRADED", 3L),
                createStatusProjection("OFFLINE", 2L),
                createStatusProjection("INITIALIZING", 1L)
        );

        when(agentRepository.countAgentsByStatus()).thenReturn(projections);
        when(healthCheckRepository.calculateAverageHealthyLatency(any(ZonedDateTime.class)))
                .thenReturn(45.6789);

        AgentHealthService.HubHealthStats stats = healthService.getHubHealthStats();

        assertThat(stats.totalAgents()).isEqualTo(16L);
        assertThat(stats.healthyAgents()).isEqualTo(10L);
        assertThat(stats.degradedAgents()).isEqualTo(3L);
        assertThat(stats.offlineAgents()).isEqualTo(2L);
        assertThat(stats.unknownAgents()).isEqualTo(1L);
        assertThat(stats.averageLatencyMs()).isEqualTo(45.7);
    }

    @Test
    @DisplayName("Should handle null average latency and return 0.0")
    void shouldHandleNullAverageLatency() {
        when(agentRepository.countAgentsByStatus()).thenReturn(List.of(
                createStatusProjection("HEALTHY", 5L)
        ));
        when(healthCheckRepository.calculateAverageHealthyLatency(any(ZonedDateTime.class)))
                .thenReturn(null);

        AgentHealthService.HubHealthStats stats = healthService.getHubHealthStats();

        assertThat(stats.totalAgents()).isEqualTo(5L);
        assertThat(stats.healthyAgents()).isEqualTo(5L);
        assertThat(stats.averageLatencyMs()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Should trigger health check on existing agent and return result")
    void shouldTriggerHealthCheckOnAgent() {
        UUID agentId = UUID.randomUUID();
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setName("MonitoredAgent");

        AgentHealthMonitor.HealthCheckResult expectedResult = new AgentHealthMonitor.HealthCheckResult(
                agentId, "HEALTHY", 25, null
        );

        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(healthMonitor.checkAgent(agent)).thenReturn(expectedResult);

        AgentHealthMonitor.HealthCheckResult actual = healthService.triggerHealthCheck(agentId);

        assertThat(actual).isEqualTo(expectedResult);
        verify(healthMonitor).checkAgent(agent);
    }

    private AgentRepository.StatusCountProjection createStatusProjection(String status, long count) {
        return new AgentRepository.StatusCountProjection() {
            @Override
            public String getStatus() {
                return status;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }
}
