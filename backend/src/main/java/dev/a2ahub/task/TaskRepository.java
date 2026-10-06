package dev.a2ahub.task;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<TaskEntity, UUID> {

    @EntityGraph(attributePaths = {"agent"})
    List<TaskEntity> findByAgentIdOrderByCreatedAtDesc(UUID agentId);

    @Override
    @EntityGraph(attributePaths = {"agent"})
    Optional<TaskEntity> findById(UUID id);

    List<TaskEntity> findByState(String state);

    void deleteByAgentId(UUID agentId);
}
