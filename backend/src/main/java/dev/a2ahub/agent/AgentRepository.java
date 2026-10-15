package dev.a2ahub.agent;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AgentRepository extends JpaRepository<Agent, UUID> {

    boolean existsByUrl(String url);

    @Query(value = """
        SELECT DISTINCT a.* FROM agents a
        LEFT JOIN agent_skills s ON s.agent_id = a.id
        WHERE (:tag IS NULL OR :tag = ANY(s.tags))
          AND (:skill IS NULL OR LOWER(s.skill_id) LIKE LOWER(CONCAT('%', :skill, '%')) OR LOWER(s.name) LIKE LOWER(CONCAT('%', :skill, '%')))
          AND (:capability IS NULL OR (a.agent_card -> 'capabilities' IS NOT NULL AND jsonb_exists(a.agent_card -> 'capabilities', :capability)))
          AND (:q IS NULL OR LOWER(a.name) LIKE LOWER(CONCAT('%', :q, '%'))
                      OR LOWER(a.description) LIKE LOWER(CONCAT('%', :q, '%'))
                      OR LOWER(s.name) LIKE LOWER(CONCAT('%', :q, '%'))
                      OR LOWER(s.description) LIKE LOWER(CONCAT('%', :q, '%')))
        ORDER BY a.name ASC
        """, countQuery = """
        SELECT COUNT(DISTINCT a.id) FROM agents a
        LEFT JOIN agent_skills s ON s.agent_id = a.id
        WHERE (:tag IS NULL OR :tag = ANY(s.tags))
          AND (:skill IS NULL OR LOWER(s.skill_id) LIKE LOWER(CONCAT('%', :skill, '%')) OR LOWER(s.name) LIKE LOWER(CONCAT('%', :skill, '%')))
          AND (:capability IS NULL OR (a.agent_card -> 'capabilities' IS NOT NULL AND jsonb_exists(a.agent_card -> 'capabilities', :capability)))
          AND (:q IS NULL OR LOWER(a.name) LIKE LOWER(CONCAT('%', :q, '%'))
                      OR LOWER(a.description) LIKE LOWER(CONCAT('%', :q, '%'))
                      OR LOWER(s.name) LIKE LOWER(CONCAT('%', :q, '%'))
                      OR LOWER(s.description) LIKE LOWER(CONCAT('%', :q, '%')))
        """, nativeQuery = true)
    Page<Agent> searchIndexedAgents(
            @Param("skill") String skill,
            @Param("tag") String tag,
            @Param("capability") String capability,
            @Param("q") String q,
            Pageable pageable
    );

    @Query(value = """
        SELECT a.id AS id,
               GREATEST(0.0, LEAST(1.0, (1.0 - (a.embedding <=> cast(:queryVector as vector))))) AS similarity_score
        FROM agents a
        WHERE a.embedding IS NOT NULL
          AND a.status IN ('HEALTHY', 'DEGRADED')
        ORDER BY a.embedding <=> cast(:queryVector as vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<AgentWithScore> searchBySemanticEmbeddingWithScore(@Param("queryVector") String queryVector, @Param("limit") int limit);

    @Query(value = """
        SELECT * FROM agents
        WHERE embedding IS NOT NULL
          AND status IN ('HEALTHY', 'DEGRADED')
        ORDER BY embedding <=> cast(:queryVector as vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<Agent> searchBySemanticEmbedding(@Param("queryVector") String queryVector, @Param("limit") int limit);

    interface AgentWithScore {
        UUID getId();
        Double getSimilarityScore();
    }

    record AgentWithScoreDto(UUID id, Double similarityScore) implements AgentWithScore {
        @Override
        public UUID getId() {
            return id;
        }

        @Override
        public Double getSimilarityScore() {
            return similarityScore;
        }
    }

    @Modifying
    @Query(value = "UPDATE agents SET embedding = cast(:embedding as vector) WHERE id = :id", nativeQuery = true)
    void updateEmbedding(@Param("id") UUID id, @Param("embedding") String embedding);

    @Query(value = """
        SELECT COALESCE(status, 'UNKNOWN') AS status, COUNT(*) AS count
        FROM agents
        GROUP BY COALESCE(status, 'UNKNOWN')
        """, nativeQuery = true)
    List<StatusCountProjection> countAgentsByStatus();

    interface StatusCountProjection {
        String getStatus();
        long getCount();
    }
}
