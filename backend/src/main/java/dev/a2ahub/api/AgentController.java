package dev.a2ahub.api;

import dev.a2ahub.agent.AgentRegistryService;
import dev.a2ahub.agent.AgentResponse;
import dev.a2ahub.agent.RegisterAgentRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {

    private final AgentRegistryService agentRegistryService;

    public AgentController(AgentRegistryService agentRegistryService) {
        this.agentRegistryService = agentRegistryService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentResponse register(@RequestBody @Valid RegisterAgentRequest request) {
        return AgentResponse.fromEntity(agentRegistryService.register(request.url()));
    }

    @GetMapping
    public List<AgentResponse> getAllAgents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return agentRegistryService.findAll(page, size).stream()
                .map(AgentResponse::fromEntity)
                .toList();
    }

    @GetMapping("/{id}")
    public AgentResponse getAgent(@PathVariable UUID id) {
        return AgentResponse.fromEntity(agentRegistryService.findById(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregister(@PathVariable UUID id) {
        agentRegistryService.unregister(id);
    }
}
