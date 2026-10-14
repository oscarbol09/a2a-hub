package dev.a2ahub.health;

import dev.a2ahub.agent.Agent;
import dev.a2ahub.agent.AgentRepository;
import dev.a2ahub.events.AgentEventPublisher;
import dev.a2ahub.events.AgentStatusEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentHealthPersistenceService Unit Tests")
class AgentHealthPersistenceServiceTest {

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private HealthCheckRepository healthCheckRepository;

    @Mock
    private AgentEventPublisher eventPublisher;

    private AgentHealthPersistenceService persistenceService;

    @BeforeEach
    void setUp() {
        persistenceService = new AgentHealthPersistenceService(
                agentRepository,
                healthCheckRepository,
                eventPublisher
        );
    }

    @Test
    @DisplayName("Should publish WebSocket event when agent status transitions")
    void shouldPublishWebSocketEventOnStatusTransition() {
        UUID agentId = UUID.randomUUID();
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setName("TestAgent");
        agent.setStatus("HEALTHY");

        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));

        persistenceService.recordProbeResult(agentId, "OFFLINE", 3000, "Connection timed out");

        assertThat(agent.getStatus()).isEqualTo("OFFLINE");
        verify(agentRepository).save(agent);
        verify(healthCheckRepository).save(any(HealthCheckLog.class));

        ArgumentCaptor<AgentStatusEvent> eventCaptor = ArgumentCaptor.forClass(AgentStatusEvent.class);
        verify(eventPublisher).publishStatusEvent(eventCaptor.capture());
        AgentStatusEvent event = eventCaptor.getValue();
        assertThat(event.status()).isEqualTo("OFFLINE");
        assertThat(event.previousStatus()).isEqualTo("HEALTHY");
    }

    @Test
    @DisplayName("Should SUPPRESS WebSocket event when agent status remains unchanged (steady state)")
    void shouldSuppressWebSocketEventWhenStatusUnchanged() {
        UUID agentId = UUID.randomUUID();
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setName("TestAgent");
        agent.setStatus("HEALTHY");

        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));

        persistenceService.recordProbeResult(agentId, "HEALTHY", 120, null);

        // State and log should be updated
        assertThat(agent.getStatus()).isEqualTo("HEALTHY");
        verify(agentRepository).save(agent);
        verify(healthCheckRepository).save(any(HealthCheckLog.class));

        // BUT eventPublisher.publishStatusEvent should NOT be called (event storm suppression)
        verify(eventPublisher, never()).publishStatusEvent(any());
    }

    @Test
    @DisplayName("Should truncate oversized error messages before saving to log")
    void shouldTruncateOversizedErrorMessages() {
        UUID agentId = UUID.randomUUID();
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setName("TestAgent");
        agent.setStatus("HEALTHY");

        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));

        String hugeError = "E".repeat(5000);
        persistenceService.recordProbeResult(agentId, "OFFLINE", 3000, hugeError);

        ArgumentCaptor<HealthCheckLog> logCaptor = ArgumentCaptor.forClass(HealthCheckLog.class);
        verify(healthCheckRepository).save(logCaptor.capture());
        HealthCheckLog savedLog = logCaptor.getValue();
        assertThat(savedLog.getErrorMsg().length()).isLessThan(1100);
        assertThat(savedLog.getErrorMsg()).endsWith("... [truncated]");
    }

    @Test
    @DisplayName("Should DISCARD stale health probe result if agent URL was re-registered/updated in-flight")
    void shouldDiscardStaleProbeWhenProbedUrlDoesNotMatchAgentUrl() {
        UUID agentId = UUID.randomUUID();
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setName("TestAgent");
        agent.setUrl("https://new-endpoint.agent.io");
        agent.setStatus("HEALTHY");

        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));

        // In-flight probe was sent to the old endpoint, which failed and returned OFFLINE
        persistenceService.recordProbeResult(agentId, "https://old-endpoint.agent.io", "OFFLINE", 3000, "Connection refused");

        // The stale probe should be discarded: agent status must remain HEALTHY, no save, no event
        assertThat(agent.getStatus()).isEqualTo("HEALTHY");
        verify(agentRepository, never()).save(agent);
        verify(healthCheckRepository, never()).save(any(HealthCheckLog.class));
        verify(eventPublisher, never()).publishStatusEvent(any());
    }
}
