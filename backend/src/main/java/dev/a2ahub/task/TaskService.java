package dev.a2ahub.task;

import dev.a2ahub.agent.Agent;
import dev.a2ahub.agent.AgentNotFoundException;
import dev.a2ahub.agent.AgentRepository;
import dev.a2ahub.events.AgentEventPublisher;
import dev.a2ahub.security.SsrfValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final TaskRepository taskRepository;
    private final AgentRepository agentRepository;
    private final SsrfValidator ssrfValidator;
    private final AgentEventPublisher eventPublisher;
    private final RestClient restClient;

    public TaskService(TaskRepository taskRepository,
                       AgentRepository agentRepository,
                       SsrfValidator ssrfValidator,
                       AgentEventPublisher eventPublisher,
                       RestClient.Builder restClientBuilder) {
        this.taskRepository = taskRepository;
        this.agentRepository = agentRepository;
        this.ssrfValidator = ssrfValidator;
        this.eventPublisher = eventPublisher;
        this.restClient = restClientBuilder.build();
    }

    public TaskDto submitTask(SubmitTaskRequest submitRequest) {
        if (submitRequest.agentId() == null) {
            throw new IllegalArgumentException("Agent ID is required to submit a task");
        }
        if (submitRequest.payload() == null || submitRequest.payload().isEmpty()) {
            throw new IllegalArgumentException("Task payload cannot be empty");
        }

        Agent agent = agentRepository.findById(submitRequest.agentId())
                .orElseThrow(() -> new AgentNotFoundException("Agent not found with ID: " + submitRequest.agentId()));

        if ("OFFLINE".equalsIgnoreCase(agent.getStatus())) {
            throw new IllegalStateException("Target agent " + agent.getName() + " is currently OFFLINE");
        }

        // Phase 1: Persist initial task in SUBMITTED state (short transaction)
        TaskEntity task = createSubmittedTask(agent, submitRequest.contextId(), submitRequest.payload());

        // Phase 2: Execute task proxying against downstream agent endpoint WITHOUT holding DB transaction
        String endpoint = agent.getUrl().endsWith("/") ? agent.getUrl() + "tasks" : agent.getUrl() + "/tasks";
        String finalState;
        Map<String, Object> responseBody = null;
        String errorMessage = null;

        try {
            ssrfValidator.validateSafeRemoteUrl(agent.getUrl());

            responseBody = restClient.post()
                    .uri(URI.create(endpoint))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(submitRequest.payload())
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            finalState = "COMPLETED";
            if (responseBody == null) {
                responseBody = Map.of("status", "ok");
            }
        } catch (Exception e) {
            log.warn("Task execution failed for agent {}: {}", agent.getName(), e.getMessage());
            finalState = "FAILED";
            errorMessage = e.getMessage();
        }

        // Phase 3: Persist final task state and response in short transaction
        TaskEntity updatedTask = updateTaskResult(task.getId(), finalState, responseBody, errorMessage);

        TaskDto dto = toDto(updatedTask);
        eventPublisher.publishTaskUpdated(dto);

        return dto;
    }

    @Transactional
    public TaskEntity createSubmittedTask(Agent agent, String contextId, Map<String, Object> payload) {
        TaskEntity task = new TaskEntity();
        task.setAgent(agent);
        task.setContextId(contextId != null ? contextId : UUID.randomUUID().toString());
        task.setRequest(payload);
        task.setState("SUBMITTED");
        return taskRepository.save(task);
    }

    @Transactional
    public TaskEntity updateTaskResult(UUID taskId, String state, Map<String, Object> response, String errorDetail) {
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("Task not found with ID: " + taskId));
        task.setState(state);
        task.setResponse(response);
        task.setErrorDetail(errorDetail);
        task.setUpdatedAt(ZonedDateTime.now());
        return taskRepository.save(task);
    }

    public TaskDto findById(UUID id) {
        TaskEntity task = taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Task not found with ID: " + id));
        return toDto(task);
    }

    public List<TaskDto> findByAgentId(UUID agentId) {
        return taskRepository.findByAgentIdOrderByCreatedAtDesc(agentId).stream()
                .map(this::toDto)
                .toList();
    }

    private TaskDto toDto(TaskEntity entity) {
        return new TaskDto(
                entity.getId(),
                entity.getAgent().getId(),
                entity.getAgent().getName(),
                entity.getContextId(),
                entity.getState(),
                entity.getRequest(),
                entity.getResponse(),
                entity.getErrorDetail(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public record SubmitTaskRequest(UUID agentId, String contextId, Map<String, Object> payload) {}
    public record TaskDto(
            UUID id,
            UUID agentId,
            String agentName,
            String contextId,
            String state,
            Map<String, Object> request,
            Map<String, Object> response,
            String errorDetail,
            ZonedDateTime createdAt,
            ZonedDateTime updatedAt
    ) {}
}
