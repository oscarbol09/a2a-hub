package dev.a2ahub.task;

import dev.a2ahub.agent.Agent;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

import dev.a2ahub.common.UuidV7;

@Entity
@Table(name = "tasks")
@Getter
@Setter
public class TaskEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id = UuidV7.generate();

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = UuidV7.generate();
        }
    }

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id")
    private Agent agent;

    @Column(name = "original_agent_id", nullable = false, updatable = false)
    private UUID originalAgentId;

    @Column(name = "original_agent_name", nullable = false, updatable = false)
    private String originalAgentName;

    @Column(name = "context_id")
    private String contextId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskState state = TaskState.SUBMITTED;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> request;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> response;

    @Column(name = "error_detail")
    private String errorDetail;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt = ZonedDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private ZonedDateTime updatedAt = ZonedDateTime.now();
}
