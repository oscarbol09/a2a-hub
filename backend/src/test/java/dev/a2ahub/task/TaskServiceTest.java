package dev.a2ahub.task;

import dev.a2ahub.agent.Agent;
import dev.a2ahub.agent.AgentNotFoundException;
import dev.a2ahub.agent.AgentRepository;
import dev.a2ahub.events.AgentEventPublisher;
import dev.a2ahub.security.SsrfValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskService Unit Tests")
class TaskServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private SsrfValidator ssrfValidator;

    @Mock
    private AgentEventPublisher eventPublisher;

    @Mock
    private RestClient.Builder restClientBuilder;

    @Mock
    private RestClient restClient;

    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private RestClient.RequestBodySpec requestBodySpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    private TaskService taskService;

    @BeforeEach
    void setUp() {
        when(restClientBuilder.build()).thenReturn(restClient);
        taskService = new TaskService(taskRepository, agentRepository, ssrfValidator, eventPublisher, restClientBuilder);
    }

    @Test
    @DisplayName("Should reject task submission when target agent is OFFLINE")
    void shouldRejectTaskWhenAgentOffline() {
        UUID agentId = UUID.randomUUID();
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setName("OfflineAgent");
        agent.setStatus("OFFLINE");

        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));

        TaskService.SubmitTaskRequest request = new TaskService.SubmitTaskRequest(agentId, "ctx-1", Map.of("query", "test"));

        assertThatThrownBy(() -> taskService.submitTask(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OFFLINE");
    }

    @Test
    @DisplayName("Should throw when submitting a task to a non-existent agent")
    void shouldThrowWhenSubmittingTaskToMissingAgent() {
        UUID agentId = UUID.randomUUID();
        when(agentRepository.findById(agentId)).thenReturn(Optional.empty());

        TaskService.SubmitTaskRequest request = new TaskService.SubmitTaskRequest(
                agentId,
                "ctx-missing-agent",
                Map.of("query", "test")
        );

        assertThatThrownBy(() -> taskService.submitTask(request))
                .isInstanceOf(AgentNotFoundException.class)
                .hasMessage("Agent not found with ID: " + agentId);

        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should successfully execute and complete task proxying")
    void shouldExecuteAndCompleteTask() {
        UUID agentId = UUID.randomUUID();
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setName("WorkerAgent");
        agent.setUrl("https://worker.agent.io");
        agent.setStatus("HEALTHY");

        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));

        TaskEntity initialTask = new TaskEntity();
        initialTask.setId(UUID.randomUUID());
        initialTask.setAgent(agent);
        initialTask.setState("SUBMITTED");
        initialTask.setRequest(Map.of("input", "hello"));

        when(taskRepository.save(any(TaskEntity.class))).thenAnswer(i -> {
            TaskEntity t = i.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID());
            return t;
        });

        when(taskRepository.findById(any(UUID.class))).thenAnswer(i -> {
            UUID id = i.getArgument(0);
            TaskEntity t = new TaskEntity();
            t.setId(id);
            t.setAgent(agent);
            t.setState("SUBMITTED");
            t.setRequest(Map.of("input", "hello"));
            return Optional.of(t);
        });

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(any(URI.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(any(ParameterizedTypeReference.class))).thenReturn(Map.of("output", "world"));

        TaskService.SubmitTaskRequest request = new TaskService.SubmitTaskRequest(agentId, "ctx-100", Map.of("input", "hello"));
        TaskService.TaskDto result = taskService.submitTask(request);

        assertThat(result).isNotNull();
        assertThat(result.state()).isEqualTo("COMPLETED");
        assertThat(result.response()).containsEntry("output", "world");
        verify(eventPublisher).publishTaskUpdated(any(TaskService.TaskDto.class));
    }
}
