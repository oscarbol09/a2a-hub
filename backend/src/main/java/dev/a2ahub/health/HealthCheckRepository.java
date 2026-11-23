package dev.a2ahub.health;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

public interface HealthCheckRepository extends JpaRepository<HealthCheckLog, Long> {

    @Query("SELECT h FROM HealthCheckLog h WHERE h.agent.id = :agentId ORDER BY h.checkedAt DESC")
    List<HealthCheckLog> findRecentByAgentId(@Param("agentId") UUID agentId, Pageable pageable);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        WITH doomed AS (
            SELECT id FROM health_checks
            WHERE checked_at < :cutoff
            ORDER BY checked_at ASC
            LIMIT :batchSize
        )
        DELETE FROM health_checks
        WHERE id IN (SELECT id FROM doomed)
    """, nativeQuery = true)
    int deleteBatchOlderThan(@Param("cutoff") ZonedDateTime cutoff, @Param("batchSize") int batchSize);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM HealthCheckLog h WHERE h.checkedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") ZonedDateTime cutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM HealthCheckLog h WHERE h.agent.id = :agentId")
    void deleteByAgentId(@Param("agentId") UUID agentId);

    @Query("SELECT AVG(h.latencyMs) FROM HealthCheckLog h WHERE h.status = 'HEALTHY' AND h.checkedAt >= :since")
    Double calculateAverageHealthyLatency(@Param("since") ZonedDateTime since);
}
