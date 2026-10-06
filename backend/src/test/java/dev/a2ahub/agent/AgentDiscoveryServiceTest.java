package dev.a2ahub.agent;

import dev.a2ahub.vector.EmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentDiscoveryService Unit Tests")
class AgentDiscoveryServiceTest {

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private AgentSkillRepository agentSkillRepository;

    @Mock
    private EmbeddingService embeddingService;

    private AgentDiscoveryService discoveryService;

    private Agent weatherAgent;
    private Agent calculatorAgent;

    @BeforeEach
    void setUp() {
        discoveryService = new AgentDiscoveryService(agentRepository, agentSkillRepository, embeddingService);

        weatherAgent = new Agent();
        weatherAgent.setId(UUID.randomUUID());
        weatherAgent.setName("WeatherAgent");
        weatherAgent.setDescription("Provides global weather and climate alerts");
        weatherAgent.setUrl("https://weather.agent.io");
        weatherAgent.setAgentCard(new AgentCard(
                "WeatherAgent",
                "Provides global weather and climate alerts",
                "https://weather.agent.io",
                "1.0.0",
                List.of(
                        new AgentCard.Skill("get_current_weather", "Current Weather", "Fetch real-time weather", List.of("weather", "forecast", "temp")),
                        new AgentCard.Skill("air_quality", "Air Quality Index", "Fetch AQI data", List.of("weather", "environment"))
                ),
                Map.of("streaming", true, "geo_location", true),
                List.of("JSON-RPC", "REST")
        ));

        calculatorAgent = new Agent();
        calculatorAgent.setId(UUID.randomUUID());
        calculatorAgent.setName("MathAgent");
        calculatorAgent.setDescription("Performs high precision calculations");
        calculatorAgent.setUrl("https://math.agent.io");
        calculatorAgent.setAgentCard(new AgentCard(
                "MathAgent",
                "Performs high precision calculations",
                "https://math.agent.io",
                "2.1.0",
                List.of(
                        new AgentCard.Skill("evaluate_expr", "Evaluate Expression", "Solves math equations", List.of("math", "algebra", "finance"))
                ),
                Map.of("big_decimal", true),
                List.of("REST")
        ));
    }

    @Test
    @DisplayName("Should query indexed agents through repository search query")
    void shouldReturnAgentsFromIndexedRepositorySearch() {
        when(agentRepository.searchIndexedAgents(eq(null), eq(null), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(weatherAgent, calculatorAgent)));

        List<Agent> results = discoveryService.discover(null, null, null, null);

        assertThat(results).hasSize(2);
    }

    @Test
    @DisplayName("Should pass filter parameters to repository search")
    void shouldFilterBySkill() {
        when(agentRepository.searchIndexedAgents(eq("air_quality"), eq(null), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(weatherAgent)));

        List<Agent> results = discoveryService.discover("air_quality", null, null, null);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getName()).isEqualTo("WeatherAgent");
    }

    @Test
    @DisplayName("Should perform semantic vector search via EmbeddingService and pgvector query")
    void shouldPerformSemanticSearch() {
        String query = "meteorological forecast";
        String mockVector = "[0.1,0.2,0.3]";

        when(embeddingService.generateEmbedding(query)).thenReturn(mockVector);
        when(agentRepository.searchBySemanticEmbedding(mockVector, 10)).thenReturn(List.of(weatherAgent));

        List<Agent> results = discoveryService.discoverSemantic(query, 10);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getName()).isEqualTo("WeatherAgent");
    }

    @Test
    @DisplayName("Should aggregate distinct skills from agentSkillRepository projections")
    void shouldGetDistinctSkills() {
        AgentSkillRepository.SkillAgentProjection p1 = new AgentSkillRepository.SkillAgentProjection(
                "get_weather",
                "Current Weather",
                "Fetch weather",
                List.of("weather"),
                weatherAgent.getId()
        );
        AgentSkillRepository.SkillAgentProjection p2 = new AgentSkillRepository.SkillAgentProjection(
                "calc",
                "Calculator",
                "Calculate equations",
                List.of("math"),
                calculatorAgent.getId()
        );

        when(agentSkillRepository.findAllSkillProjections()).thenReturn(List.of(p1, p2));

        List<AgentDiscoveryService.SkillSummary> skills = discoveryService.getDistinctSkills();

        assertThat(skills).hasSize(2);
        assertThat(skills.stream().map(AgentDiscoveryService.SkillSummary::name))
                .containsExactlyInAnyOrder("Current Weather", "Calculator");
        assertThat(skills.stream().filter(s -> "Current Weather".equals(s.name())).findFirst().orElseThrow().agentIds())
                .containsExactly(weatherAgent.getId());
    }

    @Test
    @DisplayName("Should aggregate distinct tags from PostgreSQL projection")
    void shouldGetDistinctTags() {
        AgentSkillRepository.TagCountProjection p1 = new AgentSkillRepository.TagCountProjection() {
            @Override public String getTag() { return "weather"; }
            @Override public long getCount() { return 2; }
        };
        AgentSkillRepository.TagCountProjection p2 = new AgentSkillRepository.TagCountProjection() {
            @Override public String getTag() { return "finance"; }
            @Override public long getCount() { return 1; }
        };

        when(agentSkillRepository.findDistinctTagCounts()).thenReturn(List.of(p1, p2));

        List<AgentDiscoveryService.TagSummary> tags = discoveryService.getDistinctTags();

        assertThat(tags).hasSize(2);
        assertThat(tags.getFirst().tag()).isEqualTo("weather");
        assertThat(tags.getFirst().count()).isEqualTo(2);
    }
}
