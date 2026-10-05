package dev.a2ahub.agent;

import dev.a2ahub.events.AgentEventPublisher;
import dev.a2ahub.security.SsrfValidator;
import dev.a2ahub.vector.EmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentRegistryService Unit Tests")
class AgentRegistryServiceTest {

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private AgentSkillRepository agentSkillRepository;

    @Mock
    private SsrfValidator ssrfValidator;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private AgentEventPublisher eventPublisher;

    @Mock
    private RestClient.Builder restClientBuilder;

    @Mock
    private RestClient restClient;

    @Mock
    private RestClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    private AgentRegistryService agentRegistryService;

    @BeforeEach
    void setUp() {
        when(restClientBuilder.build()).thenReturn(restClient);
        agentRegistryService = new AgentRegistryService(
                agentRepository,
                agentSkillRepository,
                ssrfValidator,
                embeddingService,
                eventPublisher,
                restClientBuilder
        );
    }

    @Test
    @DisplayName("Should reject registration if agent URL is already registered")
    void shouldRejectDuplicateAgentUrl() {
        String url = "https://agent.example.com";
        when(agentRepository.existsByUrl(url)).thenReturn(true);

        assertThatThrownBy(() -> agentRegistryService.register(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already registered");

        verify(agentRepository, never()).save(any());
        verify(ssrfValidator, never()).validateSafeRemoteUrl(any());
    }

    @Test
    @DisplayName("Should validate SSRF before registering, save normalized skills, and persist embedding")
    @SuppressWarnings("unchecked")
    void shouldRegisterAgentAndExtractSkills() {
        String url = "https://weather.example.com";
        when(agentRepository.existsByUrl(url)).thenReturn(false);
        doNothing().when(ssrfValidator).validateSafeRemoteUrl(url);

        AgentCard.Skill skill = new AgentCard.Skill("get_weather", "Get Weather", "Fetches current weather", List.of("weather", "forecast"));
        AgentCard card = new AgentCard("Weather Agent", "Weather bot", url, "1.0.0", List.of(skill), Map.of(), List.of("JSON-RPC"));

        when(restClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(URI.class))).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(AgentCard.class)).thenReturn(card);

        Agent savedAgent = new Agent();
        UUID agentId = UUID.randomUUID();
        savedAgent.setId(agentId);
        savedAgent.setName(card.name());
        savedAgent.setUrl(url);

        when(agentRepository.save(any(Agent.class))).thenReturn(savedAgent);
        when(embeddingService.generateAgentEmbedding(card)).thenReturn("[0.1,0.2]");

        Agent result = agentRegistryService.register(url);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("Weather Agent");
        verify(ssrfValidator).validateSafeRemoteUrl(url);
        verify(agentRepository).save(any(Agent.class));
        verify(agentSkillRepository).saveAll(anyList());
        verify(embeddingService).generateAgentEmbedding(card);
        verify(agentRepository).updateEmbedding(agentId, "[0.1,0.2]");
        verify(eventPublisher).publishAgentRegistered(savedAgent);
    }

    @Test
    @DisplayName("Should successfully find agent by ID")
    void shouldFindAgentById() {
        UUID id = UUID.randomUUID();
        Agent agent = new Agent();
        agent.setId(id);
        agent.setName("WeatherAgent");

        when(agentRepository.findById(id)).thenReturn(Optional.of(agent));

        Agent result = agentRegistryService.findById(id);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("WeatherAgent");
        verify(agentRepository).findById(id);
    }

    @Test
    @DisplayName("Should throw when finding non-existent agent by ID")
    void shouldThrowWhenAgentNotFound() {
        UUID id = UUID.randomUUID();
        when(agentRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> agentRegistryService.findById(id))
                .isInstanceOf(AgentNotFoundException.class)
                .hasMessageContaining("Agent not found with ID");
    }

    @Test
    @DisplayName("Should successfully list all registered agents with pagination")
    void shouldListAllAgents() {
        Agent a1 = new Agent();
        a1.setName("Agent 1");
        Agent a2 = new Agent();
        a2.setName("Agent 2");

        when(agentRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(a1, a2)));

        List<Agent> results = agentRegistryService.findAll(0, 50);

        assertThat(results).hasSize(2);
        verify(agentRepository).findAll(any(Pageable.class));
    }

    @Test
    @DisplayName("Should unregister agent by ID, cleanup skills, and broadcast event")
    void shouldUnregisterAgent() {
        UUID id = UUID.randomUUID();
        doNothing().when(agentSkillRepository).deleteByAgentId(id);
        doNothing().when(agentRepository).deleteById(id);

        agentRegistryService.unregister(id);

        verify(agentSkillRepository).deleteByAgentId(id);
        verify(agentRepository).deleteById(id);
        verify(eventPublisher).publishAgentStatusChanged(id, "UNREGISTERED");
    }
}
