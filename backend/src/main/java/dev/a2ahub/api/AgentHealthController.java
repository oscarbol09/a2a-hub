package dev.a2ahub.api;

import dev.a2ahub.health.AgentHealthMonitor;
import dev.a2ahub.health.AgentHealthService;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class AgentHealthController {

    private final AgentHealthService agentHealthService;

    public AgentHealthController(AgentHealthService agentHealthService) {
        this.agentHealthService = agentHealthService;
    }

    @GetMapping("/agents/{id}/health")
    public AgentHealthService.AgentHealthResponse getAgentHealthHistory(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "30") int limit
    ) {
        return agentHealthService.getAgentHealthHistory(id, limit);
    }

    @PostMapping("/agents/{id}/health/check")
    public AgentHealthMonitor.HealthCheckResult triggerHealthCheck(@PathVariable UUID id) {
        return agentHealthService.triggerHealthCheck(id);
    }

    @GetMapping("/health/stats")
    public AgentHealthService.HubHealthStats getHubHealthStats() {
        return agentHealthService.getHubHealthStats();
    }
}
