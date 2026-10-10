package dev.a2ahub.weather;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class WeatherAgentController {

    private final ChatClient chatClient;

    public WeatherAgentController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @GetMapping("/.well-known/agent-card.json")
    public Map<String, Object> getAgentCard() {
        return Map.of(
                "name", "WeatherAgent",
                "version", "1.0.0",
                "description", "Weather forecasting AI",
                "supportedInterfaces", List.of(
                        Map.of(
                                "url", "http://weather-agent:8081/tasks",
                                "protocolBinding", "HTTP+JSON",
                                "protocolVersion", "1.0"
                        )
                ),
                "skills", List.of(
                        Map.of(
                                "id", "weather",
                                "name", "Weather",
                                "description", "Get weather",
                                "tags", List.of("weather")
                        )
                )
        );
    }

    @PostMapping("/tasks")
    public Map<String, Object> handleTask(@RequestBody Map<String, Object> payload) {
        String query = (String) payload.getOrDefault("query", "What is the weather today?");
        String contextId = (String) payload.getOrDefault("contextId", "default");

        String response = chatClient.prompt()
                .user(query)
                .call()
                .content();

        return Map.of(
                "status", "ok",
                "result", response,
                "contextId", contextId
        );
    }
}
