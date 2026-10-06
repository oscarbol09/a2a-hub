package dev.a2ahub.agent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AgentSkillRepository extends JpaRepository<AgentSkillEntity, UUID> {

    List<AgentSkillEntity> findByAgentId(UUID agentId);

    void deleteByAgentId(UUID agentId);

    @Query("SELECT s FROM AgentSkillEntity s WHERE LOWER(s.skillId) LIKE LOWER(CONCAT('%', :skill, '%')) OR LOWER(s.name) LIKE LOWER(CONCAT('%', :skill, '%'))")
    List<AgentSkillEntity> findBySkillMatch(@Param("skill") String skill);

    @Query(value = "SELECT * FROM agent_skills WHERE :tag = ANY(tags)", nativeQuery = true)
    List<AgentSkillEntity> findByTagNative(@Param("tag") String tag);

    @Query(value = """
        SELECT t AS tag, COUNT(*) AS count
        FROM agent_skills s, unnest(s.tags) AS t
        GROUP BY t
        ORDER BY count DESC, tag ASC
        """, nativeQuery = true)
    List<TagCountProjection> findDistinctTagCounts();

    @Query("SELECT new dev.a2ahub.agent.AgentSkillRepository$SkillAgentProjection(s.skillId, s.name, s.description, s.tags, s.agent.id) FROM AgentSkillEntity s")
    List<SkillAgentProjection> findAllSkillProjections();

    interface TagCountProjection {
        String getTag();
        long getCount();
    }

    record SkillAgentProjection(
        String skillId,
        String name,
        String description,
        List<String> tags,
        UUID agentId
    ) {}
}
