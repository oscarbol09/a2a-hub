package dev.a2ahub.events;

import dev.a2ahub.agent.Agent;
import dev.a2ahub.task.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentEventPublisher Unit Tests")
class AgentEventPublisherTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private AgentEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        eventPublisher = new AgentEventPublisher(messagingTemplate);
    }

    @Test
    @DisplayName("Should publish AGENT_REGISTERED payload to /topic/agents")
    @SuppressWarnings("unchecked")
    void shouldPublishAgentRegistered() {
        UUID agentId = UUID.randomUUID();
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setName("TranslatorAgent");
        agent.setUrl("https://translator.agent.io");
        agent.setStatus("HEALTHY");

        eventPublisher.publishAgentRegistered(agent);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/agents"), payloadCaptor.capture());

        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload)
                .containsEntry("type", "AGENT_REGISTERED")
                .containsEntry("agentId", agentId)
                .containsEntry("name", "TranslatorAgent")
                .containsEntry("url", "https://translator.agent.io")
                .containsEntry("status", "HEALTHY");
        assertThat(payload.get("timestamp")).isNotNull();
    }

    @Test
    @DisplayName("Should handle exception gracefully when publishing AGENT_REGISTERED fails")
    void shouldHandleExceptionOnPublishAgentRegisteredFailure() {
        Agent agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setName("FailingAgent");
        agent.setUrl("https://failing.agent.io");
        agent.setStatus("HEALTHY");

        doThrow(new RuntimeException("STOMP broker unavailable"))
                .when(messagingTemplate).convertAndSend(eq("/topic/agents"), any(Map.class));

        // Must not throw exception
        eventPublisher.publishAgentRegistered(agent);
    }

    @Test
    @DisplayName("Should publish AGENT_STATUS_CHANGED payload to /topic/agents")
    @SuppressWarnings("unchecked")
    void shouldPublishAgentStatusChanged() {
        UUID agentId = UUID.randomUUID();

        eventPublisher.publishAgentStatusChanged(agentId, "DEGRADED");

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/agents"), payloadCaptor.capture());

        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload)
                .containsEntry("type", "AGENT_STATUS_CHANGED")
                .containsEntry("agentId", agentId)
                .containsEntry("status", "DEGRADED");
        assertThat(payload.get("timestamp")).isNotNull();
    }

    @Test
    @DisplayName("Should handle exception gracefully when publishing AGENT_STATUS_CHANGED fails")
    void shouldHandleExceptionOnPublishAgentStatusChangedFailure() {
        doThrow(new RuntimeException("STOMP error"))
                .when(messagingTemplate).convertAndSend(eq("/topic/agents"), any(Map.class));

        eventPublisher.publishAgentStatusChanged(UUID.randomUUID(), "OFFLINE");
    }

    @Test
    @DisplayName("Should publish AgentStatusEvent object to /topic/agents/status")
    void shouldPublishStatusEvent() {
        UUID agentId = UUID.randomUUID();
        AgentStatusEvent event = new AgentStatusEvent(
                agentId,
                "WeatherAgent",
                "HEALTHY",
                "UNKNOWN",
                45,
                ZonedDateTime.now(),
                "200 OK"
        );

        eventPublisher.publishStatusEvent(event);

        verify(messagingTemplate).convertAndSend(eq("/topic/agents/status"), eq(event));
    }

    @Test
    @DisplayName("Should handle exception gracefully when publishing AgentStatusEvent fails")
    void shouldHandleExceptionOnPublishStatusEventFailure() {
        AgentStatusEvent event = new AgentStatusEvent(
                UUID.randomUUID(),
                "BrokenAgent",
                "OFFLINE",
                "HEALTHY",
                0,
                ZonedDateTime.now(),
                "Failed"
        );

        doThrow(new RuntimeException("STOMP buffer overflow"))
                .when(messagingTemplate).convertAndSend(eq("/topic/agents/status"), any(AgentStatusEvent.class));

        eventPublisher.publishStatusEvent(event);
    }

    @Test
    @DisplayName("Should publish TASK_UPDATED payload to /topic/tasks")
    @SuppressWarnings("unchecked")
    void shouldPublishTaskUpdated() {
        UUID taskId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        TaskService.TaskDto taskDto = new TaskService.TaskDto(
                taskId,
                agentId,
                "TranslatorAgent",
                "ctx-123",
                "COMPLETED",
                Map.of("input", "test"),
                Map.of("output", "done"),
                null,
                ZonedDateTime.now(),
                ZonedDateTime.now()
        );

        eventPublisher.publishTaskUpdated(taskDto);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/tasks"), payloadCaptor.capture());

        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload)
                .containsEntry("type", "TASK_UPDATED")
                .containsEntry("taskId", taskId)
                .containsEntry("agentId", agentId)
                .containsEntry("state", "COMPLETED");
        assertThat(payload.get("timestamp")).isNotNull();
    }

    @Test
    @DisplayName("Should handle exception gracefully when publishing TASK_UPDATED fails")
    void shouldHandleExceptionOnPublishTaskUpdatedFailure() {
        TaskService.TaskDto taskDto = new TaskService.TaskDto(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "FailingAgent",
                "ctx-456",
                "FAILED",
                Map.of("input", "err"),
                null,
                "Connection timeout",
                ZonedDateTime.now(),
                ZonedDateTime.now()
        );

        doThrow(new RuntimeException("Queue full"))
                .when(messagingTemplate).convertAndSend(eq("/topic/tasks"), any(Map.class));

        eventPublisher.publishTaskUpdated(taskDto);
    }
}
