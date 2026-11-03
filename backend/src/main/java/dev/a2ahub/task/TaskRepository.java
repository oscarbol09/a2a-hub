package dev.a2ahub.task;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<TaskEntity, UUID> {

    @Query("SELECT t FROM TaskEntity t WHERE t.originalAgentId = :agentId ORDER BY t.createdAt DESC")
    List<TaskEntity> findByAgentIdOrderByCreatedAtDesc(@Param("agentId") UUID agentId);

    List<TaskEntity> findByState(TaskState state);

    boolean existsByAgent_IdAndStateIn(UUID agentId, Collection<TaskState> states);

    /**
     * Cursor pagination over permanent attribution, including unregistered agents.
     * The projection omits request and response payloads.
     */
    @Query("""
        SELECT new dev.a2ahub.task.TaskSummaryDto(
            t.id,
            t.originalAgentId,
            t.originalAgentName,
            t.contextId,
            t.state,
            t.errorDetail,
            t.createdAt,
            t.updatedAt
        )
        FROM TaskEntity t
        WHERE t.originalAgentId = :agentId
          AND (:cursorId IS NULL OR t.id < :cursorId)
        ORDER BY t.id DESC
    """)
    Slice<TaskSummaryDto> findSummariesByAgentId(
            @Param("agentId") UUID agentId,
            @Param("cursorId") UUID cursorId,
            Pageable pageable
    );

    void deleteByAgentId(UUID agentId);
}
