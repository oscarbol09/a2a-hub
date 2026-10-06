package dev.a2ahub.agent;

import dev.a2ahub.vector.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@Transactional(readOnly = true)
public class AgentDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(AgentDiscoveryService.class);
    private final AgentRepository agentRepository;
    private final AgentSkillRepository agentSkillRepository;
    private final EmbeddingService embeddingService;

    public AgentDiscoveryService(AgentRepository agentRepository,
                                 AgentSkillRepository agentSkillRepository,
                                 EmbeddingService embeddingService) {
        this.agentRepository = agentRepository;
        this.agentSkillRepository = agentSkillRepository;
        this.embeddingService = embeddingService;
    }

    /**
     * Executes index-backed search across agents using PostgreSQL GIN and text matching.
     */
    public List<Agent> discover(String skill, String tag, String capability, String query, int page, int size) {
        String cleanSkill = sanitizeParam(skill);
        String cleanTag = sanitizeParam(tag);
        String cleanCapability = sanitizeParam(capability);
        String cleanQuery = sanitizeParam(query);

        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        Pageable pageable = PageRequest.of(safePage, safeSize);

        Page<Agent> resultPage = agentRepository.searchIndexedAgents(
                cleanSkill,
                cleanTag,
                cleanCapability,
                cleanQuery,
                pageable
        );

        return resultPage.getContent();
    }

    public List<Agent> discover(String skill, String tag, String capability, String query) {
        return discover(skill, tag, capability, query, 0, 50);
    }

    /**
     * Executes semantic vector search using pgvector cosine distance.
     */
    public List<Agent> discoverSemantic(String query, int limit) {
        if (query == null || query.isBlank()) {
            return discover(null, null, null, null, 0, limit);
        }

        int safeLimit = Math.min(Math.max(1, limit), 50);
        String queryVector = embeddingService.generateEmbedding(query);

        if (queryVector == null) {
            return discover(null, null, null, query, 0, safeLimit);
        }

        try {
            List<Agent> results = agentRepository.searchBySemanticEmbedding(queryVector, safeLimit);
            if (!results.isEmpty()) {
                return results;
            }
        } catch (Exception e) {
            log.warn("pgvector search failed or no embeddings found: {}. Falling back to text search.", e.getMessage());
        }

        return discover(null, null, null, query, 0, safeLimit);
    }

    /**
     * Returns distinct skill catalog with aggregated agent associations.
     */
    public List<SkillSummary> getDistinctSkills() {
        List<AgentSkillRepository.SkillAgentProjection> allSkills = agentSkillRepository.findAllSkillProjections();
        Map<String, SkillSummaryBuilder> skillMap = new LinkedHashMap<>();

        for (AgentSkillRepository.SkillAgentProjection s : allSkills) {
            String key = s.skillId();
            if (key == null || key.isBlank()) {
                key = s.name();
            }
            if (key == null) continue;

            skillMap.computeIfAbsent(key, k -> new SkillSummaryBuilder(
                    k,
                    s.name(),
                    s.description(),
                    new HashSet<>(s.tags() != null ? s.tags() : List.of())
            )).addAgent(s.agentId());
        }

        return skillMap.values().stream()
                .map(SkillSummaryBuilder::build)
                .sorted(Comparator.comparing(SkillSummary::name))
                .toList();
    }

    /**
     * Returns distinct tag frequency counts computed directly by PostgreSQL unnest aggregation.
     */
    public List<TagSummary> getDistinctTags() {
        List<AgentSkillRepository.TagCountProjection> projections = agentSkillRepository.findDistinctTagCounts();

        return projections.stream()
                .map(p -> new TagSummary(p.getTag(), p.getCount()))
                .toList();
    }

    private String sanitizeParam(String param) {
        if (param == null || param.isBlank()) {
            return null;
        }
        return param.trim();
    }

    public record SkillSummary(String skillId, String name, String description, List<String> tags, long agentCount, List<UUID> agentIds) {}
    public record TagSummary(String tag, long count) {}

    private static class SkillSummaryBuilder {
        private final String skillId;
        private final String name;
        private final String description;
        private final Set<String> tags;
        private final Set<UUID> agentIds = new HashSet<>();

        public SkillSummaryBuilder(String skillId, String name, String description, Set<String> tags) {
            this.skillId = skillId;
            this.name = name;
            this.description = description;
            this.tags = tags;
        }

        public void addAgent(UUID agentId) {
            if (agentId != null) {
                this.agentIds.add(agentId);
            }
        }

        public SkillSummary build() {
            return new SkillSummary(
                    skillId,
                    name != null ? name : skillId,
                    description,
                    new ArrayList<>(tags),
                    agentIds.size(),
                    new ArrayList<>(agentIds)
            );
        }
    }
}
