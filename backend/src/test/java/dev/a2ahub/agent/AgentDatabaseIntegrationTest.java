package dev.a2ahub.agent;

import dev.a2ahub.health.HealthCheckLog;
import dev.a2ahub.health.HealthCheckRepository;
import dev.a2ahub.security.AesGcmAttributeConverter;
import dev.a2ahub.security.SecurityProperties;
import dev.a2ahub.task.TaskEntity;
import dev.a2ahub.task.TaskRepository;
import dev.a2ahub.task.TaskState;
import dev.a2ahub.task.TaskSummaryDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;


import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@EntityScan(basePackages = "dev.a2ahub")
@EnableJpaRepositories(basePackages = "dev.a2ahub")
@Import({SecurityProperties.class, AesGcmAttributeConverter.class})
@DisplayName("Database & Flyway Hermetic Integration Tests (Testcontainers + PostgreSQL)")
class AgentDatabaseIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("a2ahub_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private AgentSkillRepository agentSkillRepository;

    @Autowired
    private HealthCheckRepository healthCheckRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Test
    @DisplayName("Should persist and retrieve Agent with JSONB AgentCard and skills")
    void shouldPersistAndRetrieveAgentWithJsonb() {
        AgentCard.Skill weatherSkill = new AgentCard.Skill("get_weather", "Get Weather", "Fetches live weather", List.of("weather", "meteo"));
        AgentCard card = new AgentCard("IntegrationAgent", "Test integration agent", "https://integration.agent.io", "1.0.0", List.of(weatherSkill), Map.of("streaming", true), List.of("REST"));

        Agent agent = new Agent();
        agent.setName("IntegrationAgent");
        agent.setDescription("Test integration agent");
        agent.setUrl("https://integration.agent.io");
        agent.setVersion("1.0.0");
        agent.setStatus("HEALTHY");
        agent.setAuthType("NONE");
        agent.setAgentCard(card);

        Agent savedAgent = agentRepository.saveAndFlush(agent);
        assertThat(savedAgent.getId()).isNotNull();

        AgentSkillEntity skillEntity = new AgentSkillEntity();
        skillEntity.setAgent(savedAgent);
        skillEntity.setSkillId("get_weather");
        skillEntity.setName("Get Weather");
        skillEntity.setDescription("Fetches live weather");
        skillEntity.setTags(List.of("weather", "meteo"));

        agentSkillRepository.saveAndFlush(skillEntity);

        // Verify retrieval
        Optional<Agent> retrieved = agentRepository.findById(savedAgent.getId());
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getName()).isEqualTo("IntegrationAgent");
        assertThat(retrieved.get().getAgentCard().skills()).hasSize(1);
        assertThat(retrieved.get().getAgentCard().skills().getFirst().id()).isEqualTo("get_weather");

        List<AgentSkillEntity> skills = agentSkillRepository.findByAgentId(savedAgent.getId());
        assertThat(skills).hasSize(1);
        assertThat(skills.getFirst().getSkillId()).isEqualTo("get_weather");

        List<AgentSkillRepository.SkillAgentProjection> projections = agentSkillRepository.findAllSkillProjections();
        assertThat(projections).isNotEmpty();
        assertThat(projections.stream().anyMatch(p -> "get_weather".equals(p.skillId()) && savedAgent.getId().equals(p.agentId()))).isTrue();

        // Verify searchIndexedAgents with jsonb_exists and GIN array query
        Page<Agent> searched = agentRepository.searchIndexedAgents(
                "get_weather",
                "weather",
                "streaming",
                "IntegrationAgent",
                PageRequest.of(0, 10)
        );
        assertThat(searched.getContent()).isNotEmpty();
        assertThat(searched.getContent().getFirst().getName()).isEqualTo("IntegrationAgent");

        // Cleanup
        agentSkillRepository.deleteByAgentId(savedAgent.getId());
        agentSkillRepository.flush();
        agentRepository.deleteById(savedAgent.getId());
        agentRepository.flush();

        assertThat(agentRepository.findById(savedAgent.getId())).isEmpty();
        assertThat(agentSkillRepository.findByAgentId(savedAgent.getId())).isEmpty();
    }

    @Test
    @DisplayName("Should persist and retrieve health check entries and tasks")
    void shouldPersistHealthChecksAndTasks() {
        Agent agent = new Agent();
        agent.setName("TaskAgent");
        agent.setUrl("https://task.agent.io");
        agent.setStatus("HEALTHY");
        agent.setAgentCard(new AgentCard("TaskAgent", "desc", "https://task.agent.io", "1.0", List.of(), Map.of(), List.of()));
        Agent savedAgent = agentRepository.saveAndFlush(agent);

        // Persist HealthCheck
        HealthCheckLog check = new HealthCheckLog(savedAgent, "HEALTHY", 45, null);
        HealthCheckLog savedCheck = healthCheckRepository.saveAndFlush(check);
        assertThat(savedCheck.getId()).isNotNull();

        List<HealthCheckLog> checks = healthCheckRepository.findRecentByAgentId(savedAgent.getId(), PageRequest.of(0, 10));
        assertThat(checks).hasSize(1);
        assertThat(checks.getFirst().getLatencyMs()).isEqualTo(45);

        // Persist Task
        TaskEntity task = new TaskEntity();
        task.setAgent(savedAgent);
        task.setContextId("integration-ctx-1");
        task.setState(TaskState.WORKING);
        task.setRequest(Map.of("prompt", "analyze dataset"));
        TaskEntity savedTask = taskRepository.saveAndFlush(task);
        assertThat(savedTask.getId()).isNotNull();

        List<TaskEntity> tasks = taskRepository.findByAgentIdOrderByCreatedAtDesc(savedAgent.getId());
        assertThat(tasks).hasSize(1);
        assertThat(tasks.getFirst().getState()).isEqualTo(TaskState.WORKING);

        // Cleanup
        taskRepository.delete(savedTask);
        taskRepository.flush();
        healthCheckRepository.delete(savedCheck);
        healthCheckRepository.flush();
        agentRepository.delete(savedAgent);
        agentRepository.flush();
    }

    @Test
    @DisplayName("Should verify optimistic locking lockVersion starts at 0L and increments to 1L on flush")
    void shouldVerifyOptimisticLockingIncrementsLockVersion() {
        Agent agent = new Agent();
        agent.setName("VersionAgent");
        agent.setUrl("https://version.agent.io");
        agent.setStatus("HEALTHY");
        agent.setAgentCard(new AgentCard("VersionAgent", "desc", "https://version.agent.io", "1.0", List.of(), Map.of(), List.of()));

        Agent saved = agentRepository.saveAndFlush(agent);
        assertThat(saved.getLockVersion()).isEqualTo(0L);

        saved.setDescription("Updated description for optimistic lock test");
        Agent updated = agentRepository.saveAndFlush(saved);
        assertThat(updated.getLockVersion()).isEqualTo(1L);

        // Cleanup
        agentRepository.delete(updated);
        agentRepository.flush();
    }

    @Test
    @DisplayName("Should perform Keyset cursor pagination on tasks using UUIDv7 cursor seek")
    void shouldPerformKeysetCursorPaginationOnTasks() throws Exception {
        Agent agent = new Agent();
        agent.setName("KeysetAgent");
        agent.setUrl("https://keyset.agent.io");
        agent.setStatus("HEALTHY");
        agent.setAgentCard(new AgentCard("KeysetAgent", "desc", "https://keyset.agent.io", "1.0", List.of(), Map.of(), List.of()));
        Agent savedAgent = agentRepository.saveAndFlush(agent);

        TaskEntity t1 = new TaskEntity();
        t1.setAgent(savedAgent);
        t1.setContextId("ctx-1");
        t1.setState(TaskState.COMPLETED);
        t1.setRequest(Map.of("step", 1));
        t1 = taskRepository.saveAndFlush(t1);

        Thread.sleep(5);

        TaskEntity t2 = new TaskEntity();
        t2.setAgent(savedAgent);
        t2.setContextId("ctx-2");
        t2.setState(TaskState.COMPLETED);
        t2.setRequest(Map.of("step", 2));
        t2 = taskRepository.saveAndFlush(t2);

        Thread.sleep(5);

        TaskEntity t3 = new TaskEntity();
        t3.setAgent(savedAgent);
        t3.setContextId("ctx-3");
        t3.setState(TaskState.COMPLETED);
        t3.setRequest(Map.of("step", 3));
        t3 = taskRepository.saveAndFlush(t3);

        // Page 1: cursorId = null, size = 2 (returns t3, t2)
        Slice<TaskSummaryDto> page1 = taskRepository.findSummariesByAgentId(savedAgent.getId(), null, PageRequest.of(0, 2));
        assertThat(page1.getContent()).hasSize(2);
        assertThat(page1.getContent().get(0).id()).isEqualTo(t3.getId());
        assertThat(page1.getContent().get(1).id()).isEqualTo(t2.getId());
        assertThat(page1.hasNext()).isTrue();

        // Page 2: cursorId = t2.getId(), size = 2 (returns t1)
        UUID cursor = page1.getContent().get(1).id();
        Slice<TaskSummaryDto> page2 = taskRepository.findSummariesByAgentId(savedAgent.getId(), cursor, PageRequest.of(0, 2));
        assertThat(page2.getContent()).hasSize(1);
        assertThat(page2.getContent().get(0).id()).isEqualTo(t1.getId());
        assertThat(page2.hasNext()).isFalse();

        // Cleanup
        taskRepository.deleteByAgentId(savedAgent.getId());
        taskRepository.flush();
        agentRepository.delete(savedAgent);
        agentRepository.flush();
    }

    @Test
    @DisplayName("Should execute Flyway V3 chunked batch pruning CTE on health checks")
    void shouldPruneHealthChecksUsingChunkedCte() {
        Agent agent = new Agent();
        agent.setName("PruningAgent");
        agent.setUrl("https://pruning.agent.io");
        agent.setStatus("HEALTHY");
        agent.setAgentCard(new AgentCard("PruningAgent", "desc", "https://pruning.agent.io", "1.0", List.of(), Map.of(), List.of()));
        Agent savedAgent = agentRepository.saveAndFlush(agent);

        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime oldTime = now.minusDays(5);

        HealthCheckLog oldLog1 = new HealthCheckLog(savedAgent, "HEALTHY", 30, null);
        oldLog1.setCheckedAt(oldTime.minusMinutes(10));
        HealthCheckLog oldLog2 = new HealthCheckLog(savedAgent, "HEALTHY", 35, null);
        oldLog2.setCheckedAt(oldTime.minusMinutes(5));
        HealthCheckLog oldLog3 = new HealthCheckLog(savedAgent, "HEALTHY", 40, null);
        oldLog3.setCheckedAt(oldTime.minusMinutes(1));
        HealthCheckLog recentLog = new HealthCheckLog(savedAgent, "HEALTHY", 25, null);
        recentLog.setCheckedAt(now);

        healthCheckRepository.saveAllAndFlush(List.of(oldLog1, oldLog2, oldLog3, recentLog));

        // Cutoff is 1 day ago. 3 logs are older than cutoff.
        ZonedDateTime cutoff = now.minusDays(1);

        // Batch 1: prune with batchSize = 2
        int deletedFirstBatch = healthCheckRepository.deleteBatchOlderThan(cutoff, 2);
        assertThat(deletedFirstBatch).isEqualTo(2);

        // Batch 2: prune remaining (should delete 1)
        int deletedSecondBatch = healthCheckRepository.deleteBatchOlderThan(cutoff, 2);
        assertThat(deletedSecondBatch).isEqualTo(1);

        // Batch 3: no more old records to prune
        int deletedThirdBatch = healthCheckRepository.deleteBatchOlderThan(cutoff, 2);
        assertThat(deletedThirdBatch).isEqualTo(0);

        // Verify recentLog remains
        List<HealthCheckLog> remaining = healthCheckRepository.findRecentByAgentId(savedAgent.getId(), PageRequest.of(0, 10));
        assertThat(remaining).hasSize(1);
        assertThat(remaining.getFirst().getId()).isEqualTo(recentLog.getId());

        // Cleanup
        healthCheckRepository.deleteByAgentId(savedAgent.getId());
        healthCheckRepository.flush();
        agentRepository.delete(savedAgent);
        agentRepository.flush();
    }
}
