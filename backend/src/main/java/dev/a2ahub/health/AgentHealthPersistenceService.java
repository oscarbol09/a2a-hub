package dev.a2ahub.health;

import dev.a2ahub.agent.Agent;
import dev.a2ahub.agent.AgentRepository;
import dev.a2ahub.events.AgentEventPublisher;
import dev.a2ahub.events.AgentStatusEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Dedicated Transactional Worker for health probe persistence.
 * Separated from AgentHealthMonitor to:
 * 1. Ensure invocation through Spring AOP CGLIB dynamic proxy (eliminating self-invocation bypass).
 * 2. Keep DB transaction duration minimal (< 2ms) without holding connections during HTTP I/O.
 * 3. Suppress redundant WebSocket event storms unless agent status actually transitioned.
 */
@Service
public class AgentHealthPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(AgentHealthPersistenceService.class);
    private static final int MAX_ERROR_MSG_LENGTH = 1024;

    private final AgentRepository agentRepository;
    private final HealthCheckRepository healthCheckRepository;
    private final AgentEventPublisher eventPublisher;

    public AgentHealthPersistenceService(AgentRepository agentRepository,
                                         HealthCheckRepository healthCheckRepository,
                                         AgentEventPublisher eventPublisher) {
        this.agentRepository = agentRepository;
        this.healthCheckRepository = healthCheckRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Records a probe result in an isolated transaction.
     * Leases a HikariCP connection only for the sub-millisecond duration of writing to DB.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordProbeResult(UUID agentId, String newStatus, int latencyMs, String errorMessage) {
        recordProbeResult(agentId, null, newStatus, latencyMs, errorMessage);
    }

    /**
     * Records a probe result in an isolated transaction with URL invariant fencing.
     * If the agent was re-registered or mutated in-flight (probedUrl != agent.getUrl()),
     * the stale probe result is safely discarded to prevent overwriting the new registration.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordProbeResult(UUID agentId, String probedUrl, String newStatus, int latencyMs, String errorMessage) {
        try {
            Agent agent = agentRepository.findById(agentId).orElse(null);
            if (agent == null) {
                log.warn("Agent {} not found during health result recording; skipping", agentId);
                return;
            }

            // Fencing: Discard stale probe if agent endpoint was re-registered or updated while probe was in flight
            if (probedUrl != null && !Objects.equals(agent.getUrl(), probedUrl)) {
                log.warn("Discarding stale health probe result for agent {} ({}): probed URL [{}] does not match current registered URL [{}]",
                        agent.getName(), agentId, probedUrl, agent.getUrl());
                return;
            }

            String previousStatus = agent.getStatus();
            boolean statusChanged = !Objects.equals(previousStatus, newStatus);

            // 1. Update Agent entity
            agent.setStatus(newStatus);
            if ("HEALTHY".equals(newStatus) || "DEGRADED".equals(newStatus)) {
                agent.setLastSeenAt(ZonedDateTime.now());
            }
            agentRepository.save(agent);

            // 2. Sanitize error message and insert HealthCheckLog
            String sanitizedError = sanitizeErrorMessage(errorMessage);
            HealthCheckLog logRecord = new HealthCheckLog(agent, newStatus, latencyMs, sanitizedError);
            healthCheckRepository.save(logRecord);

            // 3. Flapping / Event Storm Prevention: Publish WebSocket only on state change
            if (statusChanged) {
                log.info("Agent {} ({}) status transitioned: {} -> {}",
                        agent.getName(), agentId, previousStatus, newStatus);

                AgentStatusEvent event = new AgentStatusEvent(
                        agent.getId(),
                        agent.getName(),
                        newStatus,
                        previousStatus,
                        latencyMs,
                        ZonedDateTime.now(),
                        sanitizedError != null ? sanitizedError : "Status transitioned to " + newStatus
                );
                eventPublisher.publishStatusEvent(event);
            } else {
                log.trace("Agent {} state unchanged ({}). WebSocket event suppressed to prevent client DOM thrashing.",
                        agentId, newStatus);
            }
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("Optimistic locking conflict recording health check for agent {}: concurrent update detected", agentId);
        } catch (Exception e) {
            log.error("Failed to persist health probe result for agent {}: {}", agentId, e.getMessage(), e);
        }
    }

    private String sanitizeErrorMessage(String errorMsg) {
        if (errorMsg == null) {
            return null;
        }
        return errorMsg.length() > MAX_ERROR_MSG_LENGTH
                ? errorMsg.substring(0, MAX_ERROR_MSG_LENGTH) + "... [truncated]"
                : errorMsg;
    }
}
