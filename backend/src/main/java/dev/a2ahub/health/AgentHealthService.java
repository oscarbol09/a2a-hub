package dev.a2ahub.health;

import dev.a2ahub.agent.Agent;
import dev.a2ahub.agent.AgentNotFoundException;
import dev.a2ahub.agent.AgentRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AgentHealthService {

    private final AgentRepository agentRepository;
    private final HealthCheckRepository healthCheckRepository;
    private final AgentHealthMonitor healthMonitor;

    public AgentHealthService(AgentRepository agentRepository,
                              HealthCheckRepository healthCheckRepository,
                              AgentHealthMonitor healthMonitor) {
        this.agentRepository = agentRepository;
        this.healthCheckRepository = healthCheckRepository;
        this.healthMonitor = healthMonitor;
    }

    public AgentHealthResponse getAgentHealthHistory(UUID agentId, int limit) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new AgentNotFoundException("Agent not found with ID: " + agentId));

        int fetchLimit = Math.min(Math.max(limit, 1), 100);
        List<HealthCheckLog> logs = healthCheckRepository.findRecentByAgentId(agentId, PageRequest.of(0, fetchLimit));

        List<HealthCheckItem> history = logs.stream()
                .map(l -> new HealthCheckItem(l.getId(), l.getCheckedAt(), l.getStatus(), l.getLatencyMs(), l.getErrorMsg()))
                .toList();

        return new AgentHealthResponse(
                agent.getId(),
                agent.getName(),
                agent.getStatus(),
                agent.getLastSeenAt(),
                history
        );
    }

    @Transactional
    public AgentHealthMonitor.HealthCheckResult triggerHealthCheck(UUID agentId) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new AgentNotFoundException("Agent not found with ID: " + agentId));

        return healthMonitor.checkAgent(agent);
    }

    public HubHealthStats getHubHealthStats() {
        List<AgentRepository.StatusCountProjection> statusCounts = agentRepository.countAgentsByStatus();
        long total = 0;
        long healthy = 0;
        long degraded = 0;
        long offline = 0;
        long unknown = 0;

        for (AgentRepository.StatusCountProjection sc : statusCounts) {
            long count = sc.getCount();
            total += count;
            String status = sc.getStatus() != null ? sc.getStatus().toUpperCase() : "UNKNOWN";
            switch (status) {
                case "HEALTHY" -> healthy = count;
                case "DEGRADED" -> degraded = count;
                case "OFFLINE" -> offline = count;
                default -> unknown += count;
            }
        }

        Double avgLatency = healthCheckRepository.calculateAverageHealthyLatency(ZonedDateTime.now().minusHours(1));

        return new HubHealthStats(
                total,
                healthy,
                degraded,
                offline,
                unknown,
                avgLatency != null ? Math.round(avgLatency * 10.0) / 10.0 : 0.0
        );
    }

    public record AgentHealthResponse(
            UUID agentId,
            String agentName,
            String currentStatus,
            ZonedDateTime lastSeenAt,
            List<HealthCheckItem> history
    ) {}

    public record HealthCheckItem(
            Long id,
            ZonedDateTime checkedAt,
            String status,
            Integer latencyMs,
            String errorMessage
    ) {}

    public record HubHealthStats(
            long totalAgents,
            long healthyAgents,
            long degradedAgents,
            long offlineAgents,
            long unknownAgents,
            double averageLatencyMs
    ) {}
}
