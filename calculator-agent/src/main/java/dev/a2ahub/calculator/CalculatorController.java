package dev.a2ahub.calculator;

import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class CalculatorController {

    private final GoogleAiGeminiChatModel chatModel;

    public CalculatorController(@Value("${gemini.api-key}") String apiKey) {
        this.chatModel = GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gemini-3.8-flash")
                .build();
    }

    @GetMapping("/.well-known/agent-card.json")
    public Map<String, Object> getAgentCard() {
        return Map.of(
            "name", "CalculatorAgent",
            "version", "1.0.0",
            "description", "Math calculator AI",
            "supportedInterfaces", List.of(
                Map.of(
                    "url", "http://calculator-agent:8082/tasks",
                    "protocolBinding", "HTTP+JSON",
                    "protocolVersion", "1.0"
                )
            ),
            "skills", List.of(
                Map.of(
                    "id", "math",
                    "name", "Math",
                    "description", "Calculate math",
                    "tags", List.of("math", "calculator")
                )
            )
        );
    }

    @PostMapping("/tasks")
    public Map<String, Object> processTask(@RequestBody Map<String, String> payload) {
        String query = payload.get("query");
        String response = chatModel.generate(query);
        return Map.of(
            "status", "ok",
            "result", response
        );
    }
}
