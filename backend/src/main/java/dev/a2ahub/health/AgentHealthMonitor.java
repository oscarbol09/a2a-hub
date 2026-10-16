package dev.a2ahub.health;

import dev.a2ahub.agent.Agent;
import dev.a2ahub.agent.AgentCard;
import dev.a2ahub.agent.AgentRepository;
import dev.a2ahub.security.SsrfValidator;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.a2ahub.config.RestClientConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Non-transactional Orchestrator for agent health checks.
 * Dispatches concurrent network probes across Virtual Threads with bounded concurrency
 * via a Semaphore and circuit breaking per agent host.
 * Keeps HikariCP connections completely unleased during blocking HTTP I/O.
 */
@Service
public class AgentHealthMonitor {

    private static final Logger log = LoggerFactory.getLogger(AgentHealthMonitor.class);
    private static final int PROBE_TIMEOUT_MS = 3000;
    private static final int DEGRADED_LATENCY_THRESHOLD_MS = 2000;
    private static final int CYCLE_HARD_TIMEOUT_SECONDS = 15;
    private static final int PRUNE_BATCH_SIZE = 5000;

    private final AgentRepository agentRepository;
    private final HealthCheckRepository healthCheckRepository;
    private final AgentHealthPersistenceService persistenceService;
    private final SsrfValidator ssrfValidator;
    private final RestClient probeClient;
    private final HealthProperties healthProperties;
    private final AsyncTaskExecutor healthProbeTaskExecutor;
    private final Semaphore probeSemaphore;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final AtomicBoolean isCycleRunning = new AtomicBoolean(false);

    @Autowired
    public AgentHealthMonitor(AgentRepository agentRepository,
                              HealthCheckRepository healthCheckRepository,
                              AgentHealthPersistenceService persistenceService,
                              SsrfValidator ssrfValidator,
                              RestClient.Builder restClientBuilder,
                              HealthProperties healthProperties,
                              @Qualifier("healthProbeTaskExecutor") AsyncTaskExecutor healthProbeTaskExecutor) {
        this(agentRepository, healthCheckRepository, persistenceService, ssrfValidator, restClientBuilder,
                healthProperties, healthProbeTaskExecutor, createDefaultCircuitBreakerRegistry());
    }

    public AgentHealthMonitor(AgentRepository agentRepository,
                              HealthCheckRepository healthCheckRepository,
                              AgentHealthPersistenceService persistenceService,
                              SsrfValidator ssrfValidator,
                              RestClient.Builder restClientBuilder,
                              HealthProperties healthProperties,
                              @Qualifier("healthProbeTaskExecutor") AsyncTaskExecutor healthProbeTaskExecutor,
                              CircuitBreakerRegistry circuitBreakerRegistry) {
        this.agentRepository = agentRepository;
        this.healthCheckRepository = healthCheckRepository;
        this.persistenceService = persistenceService;
        this.ssrfValidator = ssrfValidator;
        this.healthProperties = healthProperties;
        this.healthProbeTaskExecutor = healthProbeTaskExecutor;
        this.probeSemaphore = new Semaphore(healthProperties.getMaxConcurrentProbes());
        this.circuitBreakerRegistry = circuitBreakerRegistry;

        HttpComponentsClientHttpRequestFactory requestFactory = RestClientConfig.createRequestFactory(
                ssrfValidator,
                Duration.ofMillis(PROBE_TIMEOUT_MS),
                Duration.ofMillis(PROBE_TIMEOUT_MS)
        );

        this.probeClient = restClientBuilder
                .requestFactory(requestFactory)
                .build();
    }

    private static CircuitBreakerRegistry createDefaultCircuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50.0f)
                .slidingWindowSize(5)
                .minimumNumberOfCalls(3)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    /**
     * Periodic health probe cycle across all registered agents.
     * Uses Java 21 Virtual Threads for non-blocking concurrent I/O.
     * ZERO database connections are held while HTTP requests are in flight.
     */
    @Scheduled(fixedDelayString = "#{@healthProperties.intervalMs}", initialDelay = 10000)
    public void runHealthChecks() {
        if (!isCycleRunning.compareAndSet(false, true)) {
            log.warn("Health probe cycle is already in progress. Skipping duplicate schedule trigger.");
            return;
        }

        try {
            List<AgentTarget> targets = agentRepository.findAll().stream()
                    .map(agent -> new AgentTarget(agent.getId(), agent.getName(), agent.getUrl()))
                    .toList();

            if (targets.isEmpty()) {
                return;
            }

            log.info("Starting concurrent health probe cycle for {} registered agents", targets.size());

            List<CompletableFuture<Void>> futures = targets.stream()
                    .map(target -> CompletableFuture.supplyAsync(
                            () -> executeProbe(target),
                            healthProbeTaskExecutor
                    ).thenAccept(result -> {
                        try {
                            persistenceService.recordProbeResult(
                                    result.agentId(),
                                    result.probedUrl(),
                                    result.status(),
                                    result.latencyMs() != null ? result.latencyMs() : 0,
                                    result.error()
                            );
                        } catch (Exception e) {
                            log.warn("Failed to persist probe result for agent {} ({}): {}", target.name(), target.id(), e.getMessage());
                        }
                    }))
                    .toList();

            CompletableFuture<Void> allOf = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));

            try {
                allOf.get(CYCLE_HARD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                log.warn("Health probe cycle reached hard timeout ceiling of {}s; in-flight probes will persist reactively upon completion", CYCLE_HARD_TIMEOUT_SECONDS);
            } catch (Exception e) {
                log.error("Unexpected error during health probe cycle aggregation: {}", e.getMessage(), e);
            }

            long completedCount = futures.stream().filter(CompletableFuture::isDone).count();
            log.info("Completed health probe cycle. Successfully awaited {}/{} agents within timeout ceiling", completedCount, targets.size());
        } finally {
            isCycleRunning.set(false);
        }
    }

    /**
     * Executes health probe for a single agent (synchronous entry point).
     */
    public HealthCheckResult checkAgent(Agent agent) {
        HealthCheckResult result = executeProbe(new AgentTarget(agent.getId(), agent.getName(), agent.getUrl()));
        persistenceService.recordProbeResult(
                agent.getId(),
                agent.getUrl(),
                result.status(),
                result.latencyMs() != null ? result.latencyMs() : 0,
                result.error()
        );
        return result;
    }

    /**
     * Pure HTTP probe execution. Completely non-transactional and isolated.
     * Enforces bounded concurrency via probeSemaphore and circuit-breaking per agent host.
     */
    public HealthCheckResult executeProbe(AgentTarget target) {
        String host = extractHost(target.url());
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(host);

        if (!cb.tryAcquirePermission()) {
            log.warn("Health probe skipped for agent {} ({}): Circuit breaker OPEN for host {}",
                    target.name(), target.id(), host);
            return new HealthCheckResult(
                    target.id(),
                    target.url(),
                    "OFFLINE",
                    null,
                    "Circuit breaker OPEN: agent host " + host + " is repeatedly failing, backing off"
            );
        }

        try {
            probeSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cb.releasePermission();
            return new HealthCheckResult(target.id(), target.url(), "OFFLINE", 0, "Health probe interrupted waiting for permit");
        }

        try {
            String probeUrl = target.url().endsWith("/")
                    ? target.url() + ".well-known/agent-card.json"
                    : target.url() + "/.well-known/agent-card.json";

            long startTime = System.currentTimeMillis();
            String status;
            String errorMessage = null;
            int latencyMs;
            Throwable failureCause = null;

            try {
                ssrfValidator.validateSafeRemoteUrl(target.url());

                AgentCard card = probeClient.get()
                        .uri(URI.create(probeUrl))
                        .retrieve()
                        .body(AgentCard.class);

                latencyMs = (int) (System.currentTimeMillis() - startTime);

                if (card != null && card.name() != null) {
                    status = latencyMs > DEGRADED_LATENCY_THRESHOLD_MS ? "DEGRADED" : "HEALTHY";
                } else {
                    status = "DEGRADED";
                    errorMessage = "Agent card response missing required metadata";
                }
            } catch (Exception e) {
                failureCause = e;
                latencyMs = (int) (System.currentTimeMillis() - startTime);
                status = "OFFLINE";
                errorMessage = e.getMessage() != null ? e.getMessage() : "Connection failed";
                log.debug("Health probe failed for agent {} ({}): {}", target.name(), target.id(), errorMessage);
            }

            long duration = System.currentTimeMillis() - startTime;
            if ("OFFLINE".equals(status)) {
                cb.onError(duration, TimeUnit.MILLISECONDS,
                        failureCause != null ? failureCause : new RuntimeException(errorMessage != null ? errorMessage : "Health probe failed"));
            } else {
                cb.onSuccess(duration, TimeUnit.MILLISECONDS);
            }

            return new HealthCheckResult(target.id(), target.url(), status, latencyMs, errorMessage);
        } finally {
            probeSemaphore.release();
        }
    }

    private String extractHost(String url) {
        if (url == null || url.isBlank()) {
            return "unknown";
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host != null && !host.isBlank()) {
                return host;
            }
        } catch (Exception ignored) {
            // Fallback to raw URL if URI parsing fails
        }
        return url;
    }

    Semaphore getProbeSemaphore() {
        return probeSemaphore;
    }

    CircuitBreakerRegistry getCircuitBreakerRegistry() {
        return circuitBreakerRegistry;
    }

    /**
     * Hourly chunked pruning job to prevent unbounded growth of the health_checks table.
     * Uses keyset/batch deletion with LIMIT to avoid long-lived exclusive table locks and WAL spikes.
     */
    @Scheduled(cron = "0 0 * * * *")
    public void purgeOldHealthLogs() {
        ZonedDateTime cutoff = ZonedDateTime.now().minusHours(healthProperties.getRetentionHours());
        int totalDeleted = 0;

        while (true) {
            int deleted = healthCheckRepository.deleteBatchOlderThan(cutoff, PRUNE_BATCH_SIZE);
            totalDeleted += deleted;
            if (deleted < PRUNE_BATCH_SIZE) {
                break;
            }
            try {
                // Yield CPU and allow replication/WAL checkpoints to breathe
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (totalDeleted > 0) {
            log.info("Safely purged {} health check logs older than {} hours in chunked batches",
                    totalDeleted, healthProperties.getRetentionHours());
        }
    }

    public record AgentTarget(UUID id, String name, String url) {}

    public record HealthCheckResult(UUID agentId, String probedUrl, String status, Integer latencyMs, String error) {
        public HealthCheckResult(UUID agentId, String status, Integer latencyMs, String error) {
            this(agentId, null, status, latencyMs, error);
        }

        public HealthCheckResult(UUID agentId, String status, int latencyMs) {
            this(agentId, null, status, latencyMs, null);
        }
    }
}
