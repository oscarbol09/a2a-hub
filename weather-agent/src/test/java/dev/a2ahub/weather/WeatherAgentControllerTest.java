package dev.a2ahub.weather;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.function.Consumer;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WeatherAgentController.class)
@TestPropertySource(properties = "spring.ai.google.genai.api-key=test-real-api-key")
class WeatherAgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean(answers = Answers.RETURNS_DEEP_STUBS)
    private ChatClient.Builder chatClientBuilder;

    @Test
    @DisplayName("a) GET /.well-known/agent-card.json returns A2A v1.0 schema, skills, supportedInterfaces, capabilities")
    void testGetAgentCardSchema() throws Exception {
        mockMvc.perform(get("/.well-known/agent-card.json")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("WeatherAgent"))
                .andExpect(jsonPath("$.version").value("1.0.0"))
                .andExpect(jsonPath("$.description").value("Real-time weather forecasting and meteorological assistant"))
                .andExpect(jsonPath("$.capabilities.streaming").value(false))
                .andExpect(jsonPath("$.capabilities.pushNotifications").value(false))
                .andExpect(jsonPath("$.supportedInterfaces", hasSize(1)))
                .andExpect(jsonPath("$.supportedInterfaces[0].url").value("http://weather-agent:8081/tasks"))
                .andExpect(jsonPath("$.supportedInterfaces[0].protocolBinding").value("HTTP+JSON"))
                .andExpect(jsonPath("$.supportedInterfaces[0].protocolVersion").value("1.0"))
                .andExpect(jsonPath("$.skills", hasSize(1)))
                .andExpect(jsonPath("$.skills[0].id").value("weather"))
                .andExpect(jsonPath("$.skills[0].name").value("Weather Forecast"))
                .andExpect(jsonPath("$.skills[0].description").value("Fetches current temperature and meteorological forecasts"))
                .andExpect(jsonPath("$.skills[0].tags", hasSize(3)))
                .andExpect(jsonPath("$.skills[0].tags[0]").value("weather"))
                .andExpect(jsonPath("$.skills[0].tags[1]").value("forecast"))
                .andExpect(jsonPath("$.skills[0].tags[2]").value("meteo"));
    }

    @Test
    @DisplayName("b) POST /tasks happy path mocking chatClient prompt call content")
    void testPostTasksHappyPath() throws Exception {
        ChatClient chatClient = chatClientBuilder.defaultSystem(anyString()).build();
        when(chatClient.prompt().user(ArgumentMatchers.<Consumer<ChatClient.PromptUserSpec>>any()).call().content())
                .thenReturn("Madrid: 24°C, Sunny");

        mockMvc.perform(post("/tasks")
                .header("X-Context-Id", "ctx-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "query": "What is the weather in Madrid?",
                            "contextId": "ctx-ignored-because-of-header"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.result").value("Madrid: 24°C, Sunny"))
                .andExpect(jsonPath("$.contextId").value("ctx-123"));
    }

    @Test
    @DisplayName("c) POST /tasks validation: blank query returns 400")
    void testPostTasksValidationBlankQuery() throws Exception {
        mockMvc.perform(post("/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "query": "   ",
                            "contextId": "ctx-123"
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("query must not be blank")));
    }

    @Test
    @DisplayName("d) POST /tasks validation: query > 500 characters returns 400")
    void testPostTasksValidationQueryTooLong() throws Exception {
        String longQuery = "a".repeat(501);

        mockMvc.perform(post("/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "query": "%s",
                            "contextId": "ctx-123"
                        }
                        """.formatted(longQuery)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("query must not exceed 500 characters")));
    }

    @Test
    @DisplayName("e) Mock mode in test/dev profile activates safely without throwing")
    void testMockModeInDevOrTest() {
        org.springframework.mock.env.MockEnvironment testEnv = new org.springframework.mock.env.MockEnvironment();
        testEnv.setActiveProfiles("test");
        WeatherAgentController controller = new WeatherAgentController(chatClientBuilder, "mock-dev-key", testEnv);
        controller.validateConfiguration();

        dev.a2ahub.weather.dto.TaskResponse response = controller.handleTask(
                new dev.a2ahub.weather.dto.TaskRequest("What is the weather in Berlin?", "ctx-mock"),
                null
        );
        org.junit.jupiter.api.Assertions.assertEquals("ok", response.status());
        org.junit.jupiter.api.Assertions.assertTrue(response.result().contains("[MOCK MODE]"));
        org.junit.jupiter.api.Assertions.assertEquals("ctx-mock", response.contextId());
    }

    @Test
    @DisplayName("f) Fail-fast in prod profile when API key is mock-dev-key")
    void testProdProfileFailsFastWithMockKey() {
        org.springframework.mock.env.MockEnvironment prodEnv = new org.springframework.mock.env.MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        WeatherAgentController controller = new WeatherAgentController(chatClientBuilder, "mock-dev-key", prodEnv);

        IllegalStateException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                controller::validateConfiguration
        );
        org.junit.jupiter.api.Assertions.assertEquals(
                "GEMINI_API_KEY must be configured in production profile. Mock mode is prohibited in production.",
                ex.getMessage()
        );
    }

    @Test
    @DisplayName("g) Fail-fast in production profile when API key is blank or null")
    void testProductionProfileFailsFastWithBlankKey() {
        org.springframework.mock.env.MockEnvironment prodEnv = new org.springframework.mock.env.MockEnvironment();
        prodEnv.setActiveProfiles("production");
        WeatherAgentController controller = new WeatherAgentController(chatClientBuilder, "   ", prodEnv);

        IllegalStateException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                controller::validateConfiguration
        );
        org.junit.jupiter.api.Assertions.assertEquals(
                "GEMINI_API_KEY must be configured in production profile. Mock mode is prohibited in production.",
                ex.getMessage()
        );
    }
}
