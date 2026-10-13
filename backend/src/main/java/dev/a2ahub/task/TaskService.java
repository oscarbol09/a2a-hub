package dev.a2ahub.task;

import dev.a2ahub.agent.Agent;
import dev.a2ahub.agent.AgentNotFoundException;
import dev.a2ahub.agent.AgentRepository;
import dev.a2ahub.events.AgentEventPublisher;
import dev.a2ahub.security.SsrfValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Non-transactional Orchestrator for task proxying and asynchronous delegation.
 * Dispatches downstream calls without holding HikariCP connections.
 * Supports dual synchronous and asynchronous execution modes on Java 21 Virtual Threads.
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final TaskRepository taskRepository;
    private final AgentRepository agentRepository;
    private final TaskPersistenceService persistenceService;
    private final SsrfValidator ssrfValidator;
    private final AgentEventPublisher eventPublisher;
    private final RestClient restClient;
    private final AsyncTaskExecutor taskExecutor;

    public TaskService(TaskRepository taskRepository,
                       AgentRepository agentRepository,
                       TaskPersistenceService persistenceService,
                       SsrfValidator ssrfValidator,
                       AgentEventPublisher eventPublisher,
                       RestClient.Builder restClientBuilder,
                       @Qualifier("healthProbeTaskExecutor") AsyncTaskExecutor taskExecutor) {
        this.taskRepository = taskRepository;
        this.agentRepository = agentRepository;
        this.persistenceService = persistenceService;
        this.ssrfValidator = ssrfValidator;
        this.eventPublisher = eventPublisher;
        this.restClient = restClientBuilder.build();
        this.taskExecutor = taskExecutor;
    }

    /**
     * Backward-compatible overload defaulting async based on request payload property.
     */
    public TaskDto submitTask(SubmitTaskRequest submitRequest) {
        boolean asyncRequested = Boolean.TRUE.equals(submitRequest.async());
        return submitTask(submitRequest, asyncRequested).task();
    }

    /**
     * Dual dispatch: Submits task and executes synchronously or asynchronously via Virtual Threads.
     */
    public TaskExecutionResponse submitTask(SubmitTaskRequest submitRequest, boolean asyncRequested) {
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

        ssrfValidator.validateSafeRemoteUrl(agent.getUrl());

        // Step 1: Persist initial task in a short transaction (< 2ms) via Spring proxy
        TaskDto submittedTask = persistenceService.createSubmittedTask(
                agent,
                submitRequest.contextId(),
                submitRequest.payload()
        );

        String correlationId = MDC.get("correlationId") != null ? MDC.get("correlationId") : UUID.randomUUID().toString();
        String traceparent = MDC.get("traceparent") != null ? MDC.get("traceparent") : generateW3cTraceparent();

        if (asyncRequested) {
            String agentUrl = agent.getUrl();
            String agentName = agent.getName();
            String authType = agent.getAuthType();
            String authToken = agent.getAuthTokenEnc();
            log.info("Dispatching async task {} for agent {} to virtual thread executor", submittedTask.id(), agentName);
            taskExecutor.execute(() -> {
                try {
                    log.info("Starting background proxy execution for task {}", submittedTask.id());
                    executeDownstreamProxy(submittedTask.id(), submittedTask.contextId(), agentName, agentUrl, submitRequest.payload(), correlationId, traceparent, authType, authToken);
                    log.info("Completed background proxy execution for task {}", submittedTask.id());
                } catch (Throwable t) {
                    log.error("Fatal failure in background proxy execution for task {}: {}", submittedTask.id(), t.getMessage(), t);
                }
            });
            return new TaskExecutionResponse(submittedTask, true);
        } else {
            // Synchronous execution on current Virtual Thread
            TaskDto completedDto = executeDownstreamProxy(submittedTask.id(), submittedTask.contextId(), agent.getName(), agent.getUrl(), submitRequest.payload(), correlationId, traceparent, agent.getAuthType(), agent.getAuthTokenEnc());
            return new TaskExecutionResponse(completedDto, false);
        }
    }

    /**
     * Backward-compatible overload for proxying without downstream authentication credentials.
     */
    public TaskDto executeDownstreamProxy(UUID taskId, String contextId, String agentName, String agentUrl, Map<String, ?> payload, String correlationId, String traceparent) {
        return executeDownstreamProxy(taskId, contextId, agentName, agentUrl, payload, correlationId, traceparent, null, null);
    }

    /**
     * Dispatches task to downstream agent with optional Bearer or API_KEY authentication headers.
     */
    public TaskDto executeDownstreamProxy(UUID taskId, String contextId, String agentName, String agentUrl, Map<String, ?> payload, String correlationId, String traceparent, String authType, String authToken) {
        String endpoint = agentUrl.endsWith("/") ? agentUrl + "tasks" : agentUrl + "/tasks";
        TaskState finalState;
        Map<String, Object> responseBody = null;
        String errorMessage = null;

        try {
            RestClient.RequestBodySpec requestSpec = restClient.post()
                    .uri(URI.create(endpoint))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Correlation-Id", correlationId)
                    .header("X-Context-Id", contextId)
                    .header("X-Task-Id", taskId.toString())
                    .header("traceparent", traceparent);

            if (authToken != null && !authToken.isBlank() && authType != null) {
                String token = authToken.trim();
                if ("BEARER".equalsIgnoreCase(authType)) {
                    requestSpec.header("Authorization", token.startsWith("Bearer ") ? token : "Bearer " + token);
                } else if ("API_KEY".equalsIgnoreCase(authType)) {
                    requestSpec.header("X-API-Key", token);
                }
            }

            responseBody = requestSpec
                    .body(payload)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            finalState = TaskState.COMPLETED;
            if (responseBody == null) {
                responseBody = Map.of("status", "ok");
            }
        } catch (ResourceAccessException e) {
            log.warn("Downstream agent {} connection/read timeout for task {}: {}", agentName, taskId, e.getMessage());
            finalState = TaskState.FAILED;
            errorMessage = "Agent connection/read timeout: " + e.getMessage();
        } catch (RestClientResponseException e) {
            log.warn("Downstream agent {} returned HTTP {} for task {}: {}", agentName, e.getStatusCode(), taskId, e.getResponseBodyAsString());
            finalState = TaskState.FAILED;
            errorMessage = "Agent returned HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString();
        } catch (Exception e) {
            log.error("Unexpected failure proxying task {} to agent {}: {}", taskId, agentName, e.getMessage(), e);
            finalState = TaskState.FAILED;
            errorMessage = e.getMessage();
        }

        // Step 2: Persist final task state in a short transaction (< 2ms) via Spring proxy
        TaskDto updatedDto = persistenceService.updateTaskResult(taskId, finalState, responseBody, errorMessage);
        eventPublisher.publishTaskUpdated(updatedDto);
        return updatedDto;
    }

    public Slice<TaskSummaryDto> findSummariesByAgentId(UUID agentId, UUID cursorId, int size) {
        int pageSize = Math.min(Math.max(size, 1), 100);
        return taskRepository.findSummariesByAgentId(agentId, cursorId, PageRequest.of(0, pageSize));
    }

    @Transactional(readOnly = true)
    public TaskDto findById(UUID id) {
        TaskEntity task = taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Task not found with ID: " + id));
        return toDto(task);
    }

    @Transactional(readOnly = true)
    public List<TaskDto> findByAgentId(UUID agentId) {
        return taskRepository.findByAgentIdOrderByCreatedAtDesc(agentId).stream()
                .map(this::toDto)
                .toList();
    }

    private String generateW3cTraceparent() {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return "00-" + traceId + "-" + spanId + "-01";
    }

    public TaskDto toDto(TaskEntity entity) {
        return new TaskDto(
                entity.getId(),
                entity.getAgent().getId(),
                entity.getAgent().getName(),
                entity.getContextId(),
                entity.getState().name(),
                entity.getRequest(),
                entity.getResponse(),
                entity.getErrorDetail(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public record SubmitTaskRequest(
            @jakarta.validation.constraints.NotNull(message = "Agent ID is required") UUID agentId,
            String contextId,
            @jakarta.validation.constraints.NotEmpty(message = "Task payload cannot be empty") Map<String, ?> payload,
            Boolean async
    ) {
        public SubmitTaskRequest(UUID agentId, String contextId, Map<String, ?> payload) {
            this(agentId, contextId, payload, false);
        }
    }

    public record TaskExecutionResponse(TaskDto task, boolean isAsync) {
        public TaskExecutionResponse(TaskDto task, boolean isAsync) {
            this.task = task;
            this.isAsync = isAsync;
        }
    }

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
    ) {
        public TaskDto(
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
        ) {
            this.id = id;
            this.agentId = agentId;
            this.agentName = agentName;
            this.contextId = contextId;
            this.state = state;
            this.request = request;
            this.response = response;
            this.errorDetail = errorDetail;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }
}
