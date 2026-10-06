package dev.a2ahub.api;

import dev.a2ahub.agent.AgentDiscoveryService;
import dev.a2ahub.agent.AgentResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class DiscoveryController {

    private final AgentDiscoveryService discoveryService;

    public DiscoveryController(AgentDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    @GetMapping("/discover")
    public List<AgentResponse> discoverAgents(
            @RequestParam(required = false) String skill,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String capability,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return discoveryService.discover(skill, tag, capability, q, page, size).stream()
                .map(AgentResponse::fromEntity)
                .toList();
    }

    @GetMapping("/discover/semantic")
    public List<AgentResponse> discoverSemantic(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return discoveryService.discoverSemantic(q, limit).stream()
                .map(AgentResponse::fromEntity)
                .toList();
    }

    @GetMapping("/skills")
    public List<AgentDiscoveryService.SkillSummary> getSkills() {
        return discoveryService.getDistinctSkills();
    }

    @GetMapping("/tags")
    public List<AgentDiscoveryService.TagSummary> getTags() {
        return discoveryService.getDistinctTags();
    }
}
