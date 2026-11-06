package dev.a2ahub.task;

import dev.a2ahub.agent.Agent;
import dev.a2ahub.agent.AgentCard;
import dev.a2ahub.agent.AgentNotFoundException;
import dev.a2ahub.agent.AgentRepository;
import dev.a2ahub.events.AgentEventPublisher;
import dev.a2ahub.security.SsrfValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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
import java.util.HashMap;
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
    private final RestClient declaredTaskRestClient;
    private final AsyncTaskExecutor taskExecutor;

    public TaskService(TaskRepository taskRepository,
                       AgentRepository agentRepository,
                       TaskPersistenceService persistenceService,
                       SsrfValidator ssrfValidator,
                       AgentEventPublisher eventPublisher,
                       RestClient.Builder restClientBuilder,
                       @Qualifier("declaredTaskRestClient") RestClient declaredTaskRestClient,
                       @Qualifier("healthProbeTaskExecutor") AsyncTaskExecutor taskExecutor) {
        this.taskRepository = taskRepository;
        this.agentRepository = agentRepository;
        this.persistenceService = persistenceService;
        this.ssrfValidator = ssrfValidator;
        this.eventPublisher = eventPublisher;
        this.restClient = restClientBuilder.build();
        this.declaredTaskRestClient = declaredTaskRestClient;
        this.taskExecutor = taskExecutor;
    }

    /**
     * Startup recovery: reconciles any tasks left orphaned in SUBMITTED or WORKING state from prior crashes or restarts.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOrphanedTasksOnStartup() {
        int recovered = persistenceService.recoverOrphanedTasks();
        if (recovered > 0) {
            log.warn("Application startup recovery: reconciled {} orphaned tasks from SUBMITTED or WORKING to FAILED state.", recovered);
        }
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

        String agentUrl = agent.getUrl();
        String agentName = agent.getName();
        String authType = agent.getAuthType();
        String authToken = agent.getAuthTokenEnc();
        TaskEndpoint resolvedEndpoint = resolveTaskEndpoint(agent.getAgentCard(), agentUrl, authType, authToken);
        String endpoint = resolvedEndpoint.url();
        // Declarations are stateless even when their URL happens to equal the legacy fallback.
        RestClient taskClient = resolvedEndpoint.declared() ? declaredTaskRestClient : restClient;

        // DNS validation can perform network I/O: finish it before the admission transaction/row lock.
        ssrfValidator.validateSafeRemoteUrl(agentUrl);
        ssrfValidator.validateSafeRemoteUrl(endpoint);

        // Step 1: Persist initial task in a short transaction (< 2ms) via Spring proxy
        TaskDto submittedTask = persistenceService.createSubmittedTask(
                agent,
                submitRequest.contextId(),
                submitRequest.payload()
        );

        String correlationId = MDC.get("correlationId") != null ? MDC.get("correlationId") : UUID.randomUUID().toString();
        String traceparent = MDC.get("traceparent") != null ? MDC.get("traceparent") : generateW3cTraceparent();

        if (asyncRequested) {
            log.info("Dispatching async task {} for agent {} to virtual thread executor", submittedTask.id(), agentName);
            taskExecutor.execute(() -> {
                try {
                    //NEW: mark the task as notify WebSocket clients
                    TaskDto workingDto = persistenceService.updateTaskResult(submittedTask.id(), TaskState.WORKING, null, null);
                    eventPublisher.publishTaskUpdated(workingDto);
                    
                    log.info("Starting background proxy execution for task {}", submittedTask.id());
                    executeResolvedProxy(taskClient, submittedTask.id(), submittedTask.contextId(), agentName, endpoint, submitRequest.payload(), correlationId, traceparent, authType, authToken);
                    log.info("Completed background proxy execution for task {}", submittedTask.id());
                } catch (Throwable t) {

                    log.error("Fatal failure in background proxy execution for task {}: {}", submittedTask.id(), t.getMessage(), t);
                }
            });
            return new TaskExecutionResponse(submittedTask, true);
        } else {
            // Synchronous execution on current Virtual Thread
            TaskDto completedDto = executeResolvedProxy(taskClient, submittedTask.id(), submittedTask.contextId(), agentName, endpoint, submitRequest.payload(), correlationId, traceparent, authType, authToken);
            return new TaskExecutionResponse(completedDto, false);
        }
    }

    private record TaskEndpoint(String url, boolean declared) {}

    private TaskEndpoint resolveTaskEndpoint(AgentCard card, String agentUrl, String authType, String authToken) {
        String endpoint = null;
        boolean declaresEndpoint = false;
        for (AgentCard.AgentInterface candidate : card != null ? card.interfaces() : List.<AgentCard.AgentInterface>of()) {
            if (candidate.url() == null || candidate.url().isBlank()) {
                continue;
            }
            declaresEndpoint = true;
            if (candidate.protocolBinding() == null || "HTTP+JSON".equalsIgnoreCase(candidate.protocolBinding().trim())) {
                endpoint = candidate.url().trim();
                break;
            }
        }
        if (endpoint == null) {
            if (declaresEndpoint) {
                throw new IllegalArgumentException("Agent declares no compatible HTTP+JSON task endpoint");
            }
            endpoint = agentUrl.endsWith("/") ? agentUrl + "tasks" : agentUrl + "/tasks";
        }

        URI registered = parseTaskUri(agentUrl);
        URI target = parseTaskUri(endpoint);
        if ("https".equalsIgnoreCase(registered.getScheme()) && "http".equalsIgnoreCase(target.getScheme())) {
            throw new IllegalArgumentException("Task endpoint cannot downgrade HTTPS to HTTP");
        }
        boolean sendsCredentials = authToken != null && !authToken.isBlank()
                && ("BEARER".equalsIgnoreCase(authType) || "API_KEY".equalsIgnoreCase(authType));
        boolean sameOrigin = registered.getScheme().equalsIgnoreCase(target.getScheme())
                && registered.getHost().equalsIgnoreCase(target.getHost())
                && effectivePort(registered) == effectivePort(target);
        if (sendsCredentials && !sameOrigin) {
            throw new IllegalArgumentException("Authenticated task endpoint must share the registered agent origin");
        }
        return new TaskEndpoint(target.toString(), declaresEndpoint);
    }

    private URI parseTaskUri(String value) {
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (RuntimeException e) {
            // Do not echo untrusted URLs: userinfo or query parameters may contain secrets.
            throw new IllegalArgumentException("Invalid task endpoint URL");
        }
        if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getRawAuthority().endsWith(":")) {
            throw new IllegalArgumentException("Task endpoint must be an absolute HTTP(S) URL with a valid host/port and no userinfo or fragment");
        }
        return uri;
    }

    private int effectivePort(URI uri) {
        return uri.getPort() != -1 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
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
        return executeResolvedProxy(restClient, taskId, contextId, agentName, endpoint, payload, correlationId, traceparent, authType, authToken);
    }

    private TaskDto executeResolvedProxy(RestClient taskClient, UUID taskId, String contextId, String agentName, String endpoint, Map<String, ?> payload, String correlationId, String traceparent, String authType, String authToken) {
        TaskState finalState;
        Map<String, Object> responseBody = null;
        String errorMessage = null;

        Map<String, Object> outgoingPayload = payload != null ? new HashMap<>(payload) : new HashMap<>();
        if (contextId != null && !outgoingPayload.containsKey("contextId")) {
            outgoingPayload.put("contextId", contextId);
        }

        try {
            RestClient.RequestBodySpec requestSpec = taskClient.post()
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
                    .body(outgoingPayload)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            if (responseBody != null && "error".equalsIgnoreCase(String.valueOf(responseBody.get("status")))) {
                finalState = TaskState.FAILED;
                errorMessage = downstreamErrorMessage(responseBody);
            } else {
                finalState = TaskState.COMPLETED;
                if (responseBody == null) {
                    responseBody = Map.of("status", "ok");
                }
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

    private String downstreamErrorMessage(Map<String, Object> responseBody) {
        for (String key : List.of("result", "error", "message")) {
            Object detail = responseBody.get(key);
            if (detail != null) {
                return String.valueOf(detail);
            }
        }
        return "Downstream agent returned status: error";
    }

    private String generateW3cTraceparent() {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return "00-" + traceId + "-" + spanId + "-01";
    }

    public TaskDto toDto(TaskEntity entity) {
        return new TaskDto(
                entity.getId(),
                entity.getOriginalAgentId(),
                entity.getOriginalAgentName(),
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
            @jakarta.validation.constraints.Size(max = 255, message = "contextId must not exceed 255 characters") String contextId,
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
