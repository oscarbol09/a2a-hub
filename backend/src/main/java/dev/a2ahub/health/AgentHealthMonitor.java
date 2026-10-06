package dev.a2ahub.health;

import dev.a2ahub.agent.Agent;
import dev.a2ahub.agent.AgentCard;
import dev.a2ahub.agent.AgentRepository;
import dev.a2ahub.security.SsrfValidator;
import dev.a2ahub.events.AgentEventPublisher;
import dev.a2ahub.events.AgentStatusEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class AgentHealthMonitor {

    private static final Logger log = LoggerFactory.getLogger(AgentHealthMonitor.class);
    private static final int PROBE_TIMEOUT_MS = 3000;
    private static final int DEGRADED_LATENCY_THRESHOLD_MS = 2000;

    private final AgentRepository agentRepository;
    private final HealthCheckRepository healthCheckRepository;
    private final AgentEventPublisher eventPublisher;
    private final SsrfValidator ssrfValidator;
    private final RestClient probeClient;
    private final HealthProperties healthProperties;

    public AgentHealthMonitor(AgentRepository agentRepository,
                              HealthCheckRepository healthCheckRepository,
                              AgentEventPublisher eventPublisher,
                              SsrfValidator ssrfValidator,
                              RestClient.Builder restClientBuilder,
                              HealthProperties healthProperties) {
        this.agentRepository = agentRepository;
        this.healthCheckRepository = healthCheckRepository;
        this.eventPublisher = eventPublisher;
        this.ssrfValidator = ssrfValidator;
        this.healthProperties = healthProperties;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(PROBE_TIMEOUT_MS));
        requestFactory.setReadTimeout(Duration.ofMillis(PROBE_TIMEOUT_MS));

        this.probeClient = restClientBuilder
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Periodic health probe cycle across all registered agents.
     * Uses Java 21 Virtual Threads for non-blocking concurrent I/O.
     */
    @Scheduled(fixedDelayString = "#{@healthProperties.intervalMs}", initialDelay = 10000)
    public void runHealthChecks() {
        List<Agent> agents = agentRepository.findAll();
        if (agents.isEmpty()) {
            return;
        }

        log.info("Starting concurrent health probe cycle for {} registered agents", agents.size());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = agents.stream()
                    .map(agent -> CompletableFuture.runAsync(() -> checkAgent(agent), executor))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        log.info("Completed health probe cycle");
    }

    /**
     * Executes health probe for a single agent.
     */
    public HealthCheckResult checkAgent(Agent agent) {
        String originalStatus = agent.getStatus();
        String probeUrl = agent.getUrl().endsWith("/")
                ? agent.getUrl() + ".well-known/agent-card.json"
                : agent.getUrl() + "/.well-known/agent-card.json";

        long startTime = System.currentTimeMillis();
        String status;
        String errorMessage = null;
        int latencyMs;

        try {
            ssrfValidator.validateSafeRemoteUrl(agent.getUrl());

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
            latencyMs = (int) (System.currentTimeMillis() - startTime);
            status = "OFFLINE";
            errorMessage = e.getMessage() != null ? e.getMessage() : "Connection failed";
            log.debug("Health probe failed for agent {} ({}): {}", agent.getName(), agent.getId(), errorMessage);
        }

        // Update agent record in DB
        updateAgentStatus(agent.getId(), status, latencyMs, errorMessage, originalStatus);

        return new HealthCheckResult(agent.getId(), status, latencyMs, errorMessage);
    }

    @Transactional
    public void updateAgentStatus(UUID agentId, String status, int latencyMs, String errorMessage, String previousStatus) {
        agentRepository.findById(agentId).ifPresent(agent -> {
            agent.setStatus(status);
            if ("HEALTHY".equals(status) || "DEGRADED".equals(status)) {
                agent.setLastSeenAt(ZonedDateTime.now());
            }
            agentRepository.save(agent);

            // Record health log
            HealthCheckLog logRecord = new HealthCheckLog(agent, status, latencyMs, errorMessage);
            healthCheckRepository.save(logRecord);

            // Publish status event over WebSocket
            AgentStatusEvent event = new AgentStatusEvent(
                    agent.getId(),
                    agent.getName(),
                    status,
                    previousStatus,
                    latencyMs,
                    ZonedDateTime.now(),
                    errorMessage != null ? errorMessage : "Health probe succeeded"
            );
            eventPublisher.publishStatusEvent(event);
        });
    }

    /**
     * Hourly pruning job to prevent unbounded growth of the health_checks table.
     */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void purgeOldHealthLogs() {
        ZonedDateTime cutoff = ZonedDateTime.now().minusHours(healthProperties.getRetentionHours());
        int deleted = healthCheckRepository.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("Purged {} health check logs older than {} hours", deleted, healthProperties.getRetentionHours());
        }
    }

    public record HealthCheckResult(UUID agentId, String status, int latencyMs, String error) {}
}
