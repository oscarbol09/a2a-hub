package dev.a2ahub.task;

import dev.a2ahub.agent.Agent;
import dev.a2ahub.agent.AgentNotFoundException;
import dev.a2ahub.agent.AgentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dedicated Transactional Worker for task persistence.
 * Separated from TaskService to ensure:
 * 1. Invocation through Spring AOP CGLIB dynamic proxy (eliminating self-invocation bypass).
 * 2. Zero database connections held during downstream network HTTP proxying.
 */
@Service
public class TaskPersistenceService {

    private final TaskRepository taskRepository;
    private final AgentRepository agentRepository;

    public TaskPersistenceService(TaskRepository taskRepository, AgentRepository agentRepository) {
        this.taskRepository = taskRepository;
        this.agentRepository = agentRepository;
    }

    /**
     * Short-lived transaction to persist initial submitted task state.
     */
    @SuppressWarnings("unchecked")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TaskService.TaskDto createSubmittedTask(Agent agent, String contextId, Map<String, ?> payload) {
        // The caller's detached agent may have been unregistered since its lookup.
        Agent lockedAgent = agentRepository.findByIdForUpdate(agent.getId())
                .orElseThrow(() -> new AgentNotFoundException("Agent not found with ID: " + agent.getId()));
        if ("OFFLINE".equalsIgnoreCase(lockedAgent.getStatus())) {
            throw new IllegalStateException("Target agent " + lockedAgent.getName() + " is currently OFFLINE");
        }
        TaskEntity task = new TaskEntity();
        task.setAgent(lockedAgent);
        task.setOriginalAgentId(lockedAgent.getId());
        task.setOriginalAgentName(lockedAgent.getName());
        task.setContextId(contextId != null ? contextId : UUID.randomUUID().toString());
        task.setRequest((Map<String, Object>) (Map<?, ?>) payload);
        task.setState(TaskState.SUBMITTED);
        TaskEntity saved = taskRepository.save(task);
        return new TaskService.TaskDto(
                saved.getId(),
                saved.getOriginalAgentId(),
                saved.getOriginalAgentName(),
                saved.getContextId(),
                saved.getState().name(),
                saved.getRequest(),
                saved.getResponse(),
                saved.getErrorDetail(),
                saved.getCreatedAt(),
                saved.getUpdatedAt()
        );
    }

    /**
     * Short-lived transaction to update final task state and response.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TaskService.TaskDto updateTaskResult(UUID taskId, TaskState state, Map<String, Object> response, String errorDetail) {
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("Task not found with ID: " + taskId));
        task.setState(state);
        task.setResponse(response);
        task.setErrorDetail(errorDetail);
        task.setUpdatedAt(ZonedDateTime.now());
        TaskEntity saved = taskRepository.save(task);
        return new TaskService.TaskDto(
                saved.getId(),
                saved.getOriginalAgentId(),
                saved.getOriginalAgentName(),
                saved.getContextId(),
                saved.getState().name(),
                saved.getRequest(),
                saved.getResponse(),
                saved.getErrorDetail(),
                saved.getCreatedAt(),
                saved.getUpdatedAt()
        );
    }

    /**
     * In-flight states that can only be left behind by a process that halted mid-task.
     * SUBMITTED: persisted but not yet picked up by the executor.
     * WORKING: picked up by the background executor, downstream proxy call in progress.
     */
    private static final List<TaskState> ORPHANABLE_STATES = List.of(TaskState.SUBMITTED, TaskState.WORKING);

    /**
     * Recovers in-flight tasks left in SUBMITTED or WORKING state across application restarts.
     * Marks orphaned tasks as FAILED with descriptive error context.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverOrphanedTasks() {
        List<TaskEntity> stuckTasks = taskRepository.findByStateIn(ORPHANABLE_STATES);
        int recovered = 0;
        for (TaskEntity task : stuckTasks) {
            // Capture the original state before overwriting it so the message stays accurate.
            TaskState orphanedState = task.getState();
            task.setState(TaskState.FAILED);
            task.setErrorDetail("Recovered after application restart: task was orphaned in " + orphanedState
                    + " state when previous node process halted.");
            task.setUpdatedAt(ZonedDateTime.now());
            taskRepository.save(task);
            recovered++;
        }
        return recovered;
    }
}
