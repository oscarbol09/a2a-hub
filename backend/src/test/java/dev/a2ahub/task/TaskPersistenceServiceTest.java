package dev.a2ahub.task;

import dev.a2ahub.agent.Agent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskPersistenceService Unit Tests")
class TaskPersistenceServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @InjectMocks
    private TaskPersistenceService persistenceService;

    @Test
    @DisplayName("Should persist submitted task with explicit contextId and SUBMITTED state")
    void shouldCreateSubmittedTaskWithExplicitContextId() {
        Agent agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setName("AlphaAgent");

        Map<String, Object> payload = Map.of("input", "test prompt");

        when(taskRepository.save(any(TaskEntity.class))).thenAnswer(invocation -> {
            TaskEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(UUID.randomUUID());
            }
            return entity;
        });

        TaskService.TaskDto dto = persistenceService.createSubmittedTask(agent, "ctx-custom-123", payload);

        assertThat(dto).isNotNull();
        assertThat(dto.agentId()).isEqualTo(agent.getId());
        assertThat(dto.agentName()).isEqualTo("AlphaAgent");
        assertThat(dto.contextId()).isEqualTo("ctx-custom-123");
        assertThat(dto.state()).isEqualTo(TaskState.SUBMITTED.name());
        assertThat(dto.request()).isEqualTo(payload);
        assertThat(dto.response()).isNull();
        assertThat(dto.errorDetail()).isNull();

        ArgumentCaptor<TaskEntity> captor = ArgumentCaptor.forClass(TaskEntity.class);
        verify(taskRepository).save(captor.capture());
        TaskEntity captured = captor.getValue();
        assertThat(captured.getAgent()).isEqualTo(agent);
        assertThat(captured.getContextId()).isEqualTo("ctx-custom-123");
        assertThat(captured.getState()).isEqualTo(TaskState.SUBMITTED);
        assertThat(captured.getRequest()).isEqualTo(payload);
    }

    @Test
    @DisplayName("Should auto-generate contextId when null contextId is provided")
    void shouldAutoGenerateContextIdWhenNull() {
        Agent agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setName("BetaAgent");

        when(taskRepository.save(any(TaskEntity.class))).thenAnswer(invocation -> {
            TaskEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(UUID.randomUUID());
            }
            return entity;
        });

        TaskService.TaskDto dto = persistenceService.createSubmittedTask(agent, null, Map.of("task", "run"));

        assertThat(dto).isNotNull();
        assertThat(dto.contextId()).isNotNull().isNotBlank();
        // Assert auto-generated contextId is a valid UUID
        assertThat(UUID.fromString(dto.contextId())).isNotNull();
        assertThat(dto.state()).isEqualTo(TaskState.SUBMITTED.name());
    }

    @Test
    @DisplayName("Should update task result to COMPLETED with response payload")
    void shouldUpdateTaskResultCompleted() {
        UUID taskId = UUID.randomUUID();
        Agent agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setName("WorkerAgent");

        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setAgent(agent);
        task.setContextId("ctx-comp-1");
        task.setState(TaskState.SUBMITTED);
        task.setRequest(Map.of("data", 123));

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(TaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> response = Map.of("result", "success", "score", 0.99);
        TaskService.TaskDto updatedDto = persistenceService.updateTaskResult(taskId, TaskState.COMPLETED, response, null);

        assertThat(updatedDto.id()).isEqualTo(taskId);
        assertThat(updatedDto.state()).isEqualTo(TaskState.COMPLETED.name());
        assertThat(updatedDto.response()).isEqualTo(response);
        assertThat(updatedDto.errorDetail()).isNull();
        assertThat(task.getState()).isEqualTo(TaskState.COMPLETED);
        assertThat(task.getResponse()).isEqualTo(response);
    }

    @Test
    @DisplayName("Should update task result to FAILED with error detail")
    void shouldUpdateTaskResultFailed() {
        UUID taskId = UUID.randomUUID();
        Agent agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setName("WorkerAgent");

        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setAgent(agent);
        task.setContextId("ctx-fail-1");
        task.setState(TaskState.SUBMITTED);
        task.setRequest(Map.of("data", 456));

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(TaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TaskService.TaskDto updatedDto = persistenceService.updateTaskResult(taskId, TaskState.FAILED, null, "Downstream gateway timeout");

        assertThat(updatedDto.id()).isEqualTo(taskId);
        assertThat(updatedDto.state()).isEqualTo(TaskState.FAILED.name());
        assertThat(updatedDto.response()).isNull();
        assertThat(updatedDto.errorDetail()).isEqualTo("Downstream gateway timeout");
        assertThat(task.getState()).isEqualTo(TaskState.FAILED);
        assertThat(task.getErrorDetail()).isEqualTo("Downstream gateway timeout");
    }

    @Test
    @DisplayName("Should throw IllegalStateException when task is not found")
    void shouldThrowWhenTaskNotFound() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> persistenceService.updateTaskResult(taskId, TaskState.COMPLETED, Map.of(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Task not found with ID: " + taskId);
    }

    @Test
    @DisplayName("Should recover orphaned tasks in SUBMITTED state to FAILED")
    void shouldRecoverOrphanedTasks() {
        TaskEntity stuck1 = new TaskEntity();
        stuck1.setId(UUID.randomUUID());
        stuck1.setState(TaskState.SUBMITTED);

        TaskEntity stuck2 = new TaskEntity();
        stuck2.setId(UUID.randomUUID());
        stuck2.setState(TaskState.SUBMITTED);

        when(taskRepository.findByState(TaskState.SUBMITTED)).thenReturn(java.util.List.of(stuck1, stuck2));
        when(taskRepository.save(any(TaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        int recovered = persistenceService.recoverOrphanedTasks();

        assertThat(recovered).isEqualTo(2);
        assertThat(stuck1.getState()).isEqualTo(TaskState.FAILED);
        assertThat(stuck1.getErrorDetail()).contains("Recovered after application restart");
        assertThat(stuck2.getState()).isEqualTo(TaskState.FAILED);
        assertThat(stuck2.getErrorDetail()).contains("Recovered after application restart");
    }
}
