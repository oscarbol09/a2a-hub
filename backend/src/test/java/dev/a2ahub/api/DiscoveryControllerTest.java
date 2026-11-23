package dev.a2ahub.api;

import dev.a2ahub.agent.Agent;
import dev.a2ahub.agent.AgentDiscoveryService;
import dev.a2ahub.security.ApiKeyAuthFilter;
import dev.a2ahub.security.SecurityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DiscoveryController.class)
@Import({SecurityProperties.class, ApiKeyAuthFilter.class})
@DisplayName("DiscoveryController Web Slice Tests")
class DiscoveryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgentDiscoveryService discoveryService;

    @Test
    @DisplayName("GET /api/v1/discover - Should discover agents with query parameters and pagination")
    void shouldDiscoverAgents() throws Exception {
        Agent agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setName("SearchAgent");
        agent.setUrl("https://search.agent.io");

        when(discoveryService.discover("web_search", "news", "streaming", "technology", 0, 50))
                .thenReturn(List.of(agent));

        mockMvc.perform(get("/api/v1/discover")
                        .param("skill", "web_search")
                        .param("tag", "news")
                        .param("capability", "streaming")
                        .param("q", "technology")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("SearchAgent"))
                .andExpect(jsonPath("$[0].url").value("https://search.agent.io"))
                .andExpect(jsonPath("$[0].similarityScore").doesNotHaveJsonPath());

        verify(discoveryService).discover("web_search", "news", "streaming", "technology", 0, 50);
    }

    @Test
    @DisplayName("GET /api/v1/discover/semantic - Should perform semantic discovery")
    void shouldDiscoverSemantic() throws Exception {
        Agent agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setName("AI Researcher");
        agent.setUrl("https://ai.agent.io");

        when(discoveryService.discoverSemanticWithScores("machine learning pipelines", 10))
                .thenReturn(List.of(new AgentDiscoveryService.ScoredAgent(agent, 0.73),
                        new AgentDiscoveryService.ScoredAgent(agent, 0.0),
                        new AgentDiscoveryService.ScoredAgent(agent, null)));

        mockMvc.perform(get("/api/v1/discover/semantic")
                        .param("q", "machine learning pipelines")
                        .param("limit", "10")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("AI Researcher"))
                .andExpect(jsonPath("$[0].url").value("https://ai.agent.io"))
                .andExpect(jsonPath("$[0].similarityScore").value(0.73))
                .andExpect(jsonPath("$[1].similarityScore").value(0.0))
                .andExpect(jsonPath("$[2].similarityScore").doesNotHaveJsonPath());

        verify(discoveryService).discoverSemanticWithScores("machine learning pipelines", 10);
    }

    @Test
    @DisplayName("GET /api/v1/skills - Should return global skills summary")
    void shouldReturnSkillsSummary() throws Exception {
        AgentDiscoveryService.SkillSummary summary = new AgentDiscoveryService.SkillSummary(
                "translate_text",
                "Translate Text",
                "Translates between 50 languages",
                List.of("nlp", "translation"),
                3,
                List.of(UUID.randomUUID())
        );

        when(discoveryService.getDistinctSkills()).thenReturn(List.of(summary));

        mockMvc.perform(get("/api/v1/skills")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].skillId").value("translate_text"))
                .andExpect(jsonPath("$[0].name").value("Translate Text"))
                .andExpect(jsonPath("$[0].agentCount").value(3));

        verify(discoveryService).getDistinctSkills();
    }

    @Test
    @DisplayName("GET /api/v1/tags - Should return global tag frequencies")
    void shouldReturnTagFrequencies() throws Exception {
        AgentDiscoveryService.TagSummary tagSummary = new AgentDiscoveryService.TagSummary("finance", 5);

        when(discoveryService.getDistinctTags()).thenReturn(List.of(tagSummary));

        mockMvc.perform(get("/api/v1/tags")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tag").value("finance"))
                .andExpect(jsonPath("$[0].count").value(5));

        verify(discoveryService).getDistinctTags();
    }
}
