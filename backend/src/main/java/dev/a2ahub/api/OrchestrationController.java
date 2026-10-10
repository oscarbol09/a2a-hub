package dev.a2ahub.api;

import dev.a2ahub.orchestration.A2AOrchestrator;
import dev.a2ahub.orchestration.AiSuggestionDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orchestration")
public class OrchestrationController {

    private final A2AOrchestrator a2aOrchestrator;

    public OrchestrationController(A2AOrchestrator a2aOrchestrator) {
        this.a2aOrchestrator = a2aOrchestrator;
    }

    @PostMapping("/suggest")
    public ResponseEntity<AiSuggestionDto> suggestAgent(@RequestBody AiSuggestionRequest request) {
        if (request == null || request.getTaskDescription() == null || request.getTaskDescription().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        
        try {
            AiSuggestionDto suggestion = a2aOrchestrator.suggestAgent(request.getTaskDescription());
            return ResponseEntity.ok(suggestion);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(503).build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
