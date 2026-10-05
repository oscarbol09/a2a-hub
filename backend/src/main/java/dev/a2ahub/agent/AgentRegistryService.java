package dev.a2ahub.agent;

import dev.a2ahub.events.AgentEventPublisher;
import dev.a2ahub.security.SsrfValidator;
import dev.a2ahub.vector.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@Service
public class AgentRegistryService {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistryService.class);
    private final AgentRepository agentRepository;
    private final AgentSkillRepository agentSkillRepository;
    private final SsrfValidator ssrfValidator;
    private final EmbeddingService embeddingService;
    private final AgentEventPublisher eventPublisher;
    private final RestClient restClient;

    public AgentRegistryService(AgentRepository agentRepository,
                                AgentSkillRepository agentSkillRepository,
                                SsrfValidator ssrfValidator,
                                EmbeddingService embeddingService,
                                AgentEventPublisher eventPublisher,
                                RestClient.Builder restClientBuilder) {
        this.agentRepository = agentRepository;
        this.agentSkillRepository = agentSkillRepository;
        this.ssrfValidator = ssrfValidator;
        this.embeddingService = embeddingService;
        this.eventPublisher = eventPublisher;
        this.restClient = restClientBuilder.build();
    }

    @Transactional
    public Agent register(String agentUrl) {
        if (agentRepository.existsByUrl(agentUrl)) {
            throw new IllegalArgumentException("Agent with URL " + agentUrl + " is already registered.");
        }

        // Validate SSRF defenses before issuing outbound HTTP requests
        ssrfValidator.validateSafeRemoteUrl(agentUrl);

        String fetchUrl = agentUrl.endsWith("/")
                ? agentUrl + ".well-known/agent-card.json"
                : agentUrl + "/.well-known/agent-card.json";

        log.info("Fetching agent card from: {}", fetchUrl);

        AgentCard card;
        try {
            card = restClient.get()
                    .uri(URI.create(fetchUrl))
                    .retrieve()
                    .body(AgentCard.class);
        } catch (Exception e) {
            log.error("Failed to fetch agent card from {}", fetchUrl, e);
            throw new IllegalStateException("Failed to fetch agent card from " + fetchUrl + ": " + e.getMessage());
        }

        if (card == null || card.name() == null) {
            throw new IllegalStateException("Invalid Agent Card received from " + fetchUrl);
        }

        Agent agent = new Agent();
        agent.setName(card.name());
        agent.setDescription(card.description());
        agent.setUrl(agentUrl);
        agent.setVersion(card.version());
        agent.setAgentCard(card);
        agent.setStatus("HEALTHY");

        Agent savedAgent = agentRepository.save(agent);

        // Sync individual skills into relational table for fast index querying
        if (card.skills() != null && !card.skills().isEmpty()) {
            List<AgentSkillEntity> skillEntities = card.skills().stream().map(s -> {
                AgentSkillEntity entity = new AgentSkillEntity();
                entity.setAgent(savedAgent);
                entity.setSkillId(s.id() != null && !s.id().isBlank() ? s.id() : s.name().toLowerCase().replace(" ", "_"));
                entity.setName(s.name() != null ? s.name() : "Unnamed Skill");
                entity.setDescription(s.description());
                entity.setTags(s.tags() != null ? s.tags() : List.of());
                return entity;
            }).toList();
            agentSkillRepository.saveAll(skillEntities);
        }

        // Generate and update vector embedding for semantic discovery
        String embeddingVector = embeddingService.generateAgentEmbedding(card);
        if (embeddingVector != null) {
            try {
                agentRepository.updateEmbedding(savedAgent.getId(), embeddingVector);
            } catch (Exception e) {
                log.warn("Failed to update vector embedding for agent {}: {}", savedAgent.getId(), e.getMessage());
            }
        }

        // Broadcast registration event via WebSocket
        eventPublisher.publishAgentRegistered(savedAgent);

        return savedAgent;
    }

    public List<Agent> findAll(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        return agentRepository.findAll(PageRequest.of(
                safePage,
                safeSize,
                Sort.by("name").ascending()
        )).getContent();
    }

    public List<Agent> findAll() {
        return findAll(0, 50);
    }

    public Agent findById(UUID id) {
        return agentRepository.findById(id)
                .orElseThrow(() -> new AgentNotFoundException("Agent not found with ID: " + id));
    }

    @Transactional
    public void unregister(UUID id) {
        agentSkillRepository.deleteByAgentId(id);
        agentRepository.deleteById(id);
        eventPublisher.publishAgentStatusChanged(id, "UNREGISTERED");
    }
}
