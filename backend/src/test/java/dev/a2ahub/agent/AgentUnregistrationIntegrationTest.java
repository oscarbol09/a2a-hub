package dev.a2ahub.agent;

import dev.a2ahub.events.AgentEventPublisher;
import dev.a2ahub.health.HealthCheckLog;
import dev.a2ahub.health.HealthCheckRepository;
import dev.a2ahub.security.AesGcmAttributeConverter;
import dev.a2ahub.security.SecurityProperties;
import dev.a2ahub.security.SsrfValidator;
import dev.a2ahub.task.TaskPersistenceService;
import dev.a2ahub.task.TaskRepository;
import dev.a2ahub.task.TaskService;
import dev.a2ahub.task.TaskState;
import dev.a2ahub.vector.EmbeddingService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DataJpaTest(properties = "spring.jpa.show-sql=false")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@EntityScan(basePackages = "dev.a2ahub")
@Import({SecurityProperties.class, AesGcmAttributeConverter.class, AgentRegistryService.class,
        TaskPersistenceService.class, TaskService.class, AgentUnregistrationIntegrationTest.ClientConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AgentUnregistrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("a2ahub_unregister_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @TestConfiguration
    static class ClientConfiguration {
        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }

        @Bean(name = "healthProbeTaskExecutor")
        AsyncTaskExecutor taskExecutor() {
            return new SimpleAsyncTaskExecutor();
        }
    }

    @MockitoBean private SsrfValidator ssrfValidator;
    @MockitoBean private EmbeddingService embeddingService;
    @MockitoBean private AgentEventPublisher eventPublisher;
    @Autowired private AgentRegistryService registry;
    @Autowired private TaskPersistenceService persistence;
    @Autowired private TaskService tasks;
    @Autowired private AgentRepository agents;
    @Autowired private AgentSkillRepository skills;
    @Autowired private HealthCheckRepository healthChecks;
    @Autowired private TaskRepository taskRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    private TransactionTemplate transaction;

    @BeforeEach
    void prepareDatabase() {
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM tasks");
        jdbc.update("DELETE FROM agents");
        assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("read committed");
    }

    @ParameterizedTest
    @EnumSource(value = TaskState.class, names = {"COMPLETED", "FAILED", "CANCELLED"})
    void shouldPreserveTerminalHistoryAndQueries(TaskState state) {
        Agent agent = persistAgent("https://history.agent.test");
        var submitted = persistence.createSubmittedTask(agent, "history-context", Map.of("query", "history"));
        var finished = persistence.updateTaskResult(submitted.id(), state, Map.of("result", "retained"), "detail");
        Long version = taskVersion(submitted.id());
        var createdAt = jdbc.queryForObject("SELECT created_at FROM tasks WHERE id = ?", Timestamp.class,
                submitted.id()).toInstant();
        var updatedAt = jdbc.queryForObject("SELECT updated_at FROM tasks WHERE id = ?", Timestamp.class,
                submitted.id()).toInstant();

        registry.unregister(agent.getId());

        assertThat(jdbc.queryForObject("SELECT updated_at FROM tasks WHERE id = ?", Timestamp.class,
                submitted.id()).toInstant()).isAfter(updatedAt);
        assertThat(jdbc.queryForObject("SELECT created_at FROM tasks WHERE id = ?", Timestamp.class,
                submitted.id()).toInstant()).isEqualTo(createdAt);
        assertThat(agents.findById(agent.getId())).isEmpty();
        assertThat(skills.findByAgentId(agent.getId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM health_checks", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT agent_id FROM tasks WHERE id = ?", UUID.class, submitted.id())).isNull();
        var history = tasks.findById(submitted.id());
        assertThat(history.agentId()).isEqualTo(agent.getId());
        assertThat(history.agentName()).isEqualTo("OriginalAgent");
        assertThat(history.state()).isEqualTo(state.name());
        assertThat(history.request()).isEqualTo(finished.request());
        assertThat(history.response()).isEqualTo(finished.response());
        assertThat(history.errorDetail()).isEqualTo(finished.errorDetail());
        assertThat(history.createdAt().toInstant()).isEqualTo(finished.createdAt().toInstant());
        assertThat(taskVersion(submitted.id())).isEqualTo(version);
        assertThat(tasks.findByAgentId(agent.getId())).extracting(TaskService.TaskDto::id).containsExactly(submitted.id());
        var page = taskRepository.findSummariesByAgentId(agent.getId(), null, PageRequest.of(0, 1));
        assertThat(page.getContent()).singleElement().satisfies(summary -> {
            assertThat(summary.agentId()).isEqualTo(agent.getId());
            assertThat(summary.agentName()).isEqualTo("OriginalAgent");
        });
        verify(eventPublisher).publishAgentStatusChanged(agent.getId(), "UNREGISTERED");
    }

    @Test
    void shouldPaginateDetachedHistoryWithoutMissingOrDuplicatingTasks() {
        Agent agent = persistAgent("https://paginated-history.agent.test");
        List<UUID> submittedIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            var submitted = persistence.createSubmittedTask(agent, "history-" + i, Map.of("query", "history"));
            persistence.updateTaskResult(submitted.id(), TaskState.COMPLETED, Map.of("result", "retained"), null);
            submittedIds.add(submitted.id());
        }
        var expectedIds = submittedIds.stream().sorted(Comparator.comparing(UUID::toString).reversed()).toList();

        registry.unregister(agent.getId());

        assertThat(agents.findById(agent.getId())).isEmpty();
        List<UUID> actualIds = new ArrayList<>();
        UUID cursor = null;
        for (int pageNumber = 0; pageNumber < 3; pageNumber++) {
            var page = taskRepository.findSummariesByAgentId(agent.getId(), cursor, PageRequest.of(0, 2));
            var pageIds = page.getContent().stream().map(summary -> summary.id()).toList();
            assertThat(pageIds).containsExactlyElementsOf(expectedIds.subList(pageNumber * 2,
                    Math.min(pageNumber * 2 + 2, expectedIds.size())));
            assertThat(page.hasNext()).isEqualTo(pageNumber < 2);
            assertThat(page.getContent()).allSatisfy(summary -> {
                assertThat(summary.agentId()).isEqualTo(agent.getId());
                assertThat(summary.agentName()).isEqualTo("OriginalAgent");
                assertThat(summary.state()).isEqualTo(TaskState.COMPLETED);
            });
            actualIds.addAll(pageIds);
            cursor = pageIds.get(pageIds.size() - 1);
        }
        assertThat(actualIds).containsExactlyElementsOf(expectedIds).doesNotHaveDuplicates();
        assertThat(taskRepository.findSummariesByAgentId(agent.getId(), cursor, PageRequest.of(0, 2))).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = TaskState.class, names = {"SUBMITTED", "WORKING"})
    void shouldRejectActiveTasksWithoutChangingAnyData(TaskState state) {
        Agent agent = persistAgent("https://active.agent.test");
        var submitted = persistence.createSubmittedTask(agent, "active-context", Map.of("query", "active"));
        if (state == TaskState.WORKING) {
            persistence.updateTaskResult(submitted.id(), state, null, null);
        }
        var before = databaseSnapshot();
        clearInvocations(eventPublisher);

        assertThatThrownBy(() -> registry.unregister(agent.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("active tasks");

        assertThat(databaseSnapshot()).isEqualTo(before);
        verifyNoInteractions(eventPublisher);
        persistence.updateTaskResult(submitted.id(), TaskState.COMPLETED, Map.of("result", "finished"), null);
        assertThat(tasks.findById(submitted.id()).state()).isEqualTo("COMPLETED");
    }

    @Test
    void shouldRollbackAgentDeletionAndFkDetachmentTogether() {
        Agent agent = persistAgent("https://rollback.agent.test");
        var submitted = persistence.createSubmittedTask(agent, "rollback-context", Map.of("query", "rollback"));
        persistence.updateTaskResult(submitted.id(), TaskState.COMPLETED, Map.of("result", "done"), null);
        var before = databaseSnapshot();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            registry.unregister(agent.getId());
            entityManager.flush();
            assertThat(jdbc.queryForObject("SELECT agent_id FROM tasks WHERE id = ?", UUID.class, submitted.id())).isNull();
            throw new IllegalStateException("force rollback after SQL deletion");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("force rollback");

        assertThat(databaseSnapshot()).isEqualTo(before);
    }

    @Test
    void shouldKeepOriginalIdentityAfterRenameAndUrlReuse() {
        Agent agent = persistAgent("https://reused.agent.test");
        var submitted = persistence.createSubmittedTask(agent, "original-context", Map.of("query", "original"));
        persistence.updateTaskResult(submitted.id(), TaskState.COMPLETED, Map.of("result", "done"), null);
        transaction.executeWithoutResult(status -> agents.findById(agent.getId()).orElseThrow().setName("RenamedAgent"));

        registry.unregister(agent.getId());
        Agent replacement = persistAgent(agent.getUrl());

        assertThat(replacement.getId()).isNotEqualTo(agent.getId());
        assertThat(tasks.findById(submitted.id()).agentName()).isEqualTo("OriginalAgent");
        assertThat(tasks.findByAgentId(replacement.getId())).isEmpty();
        assertThat(tasks.findByAgentId(agent.getId())).hasSize(1);
    }

    @Test
    void shouldUnregisterTasklessAndMissingAgentsWithExistingEvents() {
        Agent agent = persistAgent("https://taskless.agent.test");
        registry.unregister(agent.getId());
        assertThat(agents.findById(agent.getId())).isEmpty();
        assertThat(skills.findByAgentId(agent.getId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM health_checks", Long.class)).isZero();
        verify(eventPublisher).publishAgentStatusChanged(agent.getId(), "UNREGISTERED");

        UUID missing = UUID.randomUUID();
        registry.unregister(missing);
        verify(eventPublisher).publishAgentStatusChanged(missing, "UNREGISTERED");
    }

    @Test
    void shouldRejectUnregistrationAfterConcurrentTaskAdmissionCommits() throws Exception {
        Agent agent = persistAgent("https://creation-first.agent.test");
        // Block the insert, after admission has acquired the parent row lock.
        try (Connection blocker = dataSource.getConnection();
             ExecutorService workers = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            int blockerPid = backendPid(blocker);
            try (var statement = blocker.createStatement()) {
                statement.execute("LOCK TABLE tasks IN SHARE MODE");
            }
            try {
                var creation = CompletableFuture.supplyAsync(() -> persistence.createSubmittedTask(
                        agent, "creation-first", Map.of("query", "concurrent")), workers);
                int creatorPid = awaitBlockedQuery("insert into tasks", blockerPid);
                var deletion = CompletableFuture.runAsync(() -> registry.unregister(agent.getId()), workers);
                awaitBlockedQuery("agents", creatorPid);

                blocker.commit();
                var submitted = creation.get(10, SECONDS);
                assertThatThrownBy(() -> deletion.get(10, SECONDS))
                        .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(IllegalStateException.class);
                assertThat(tasks.findById(submitted.id()).state()).isEqualTo("SUBMITTED");
                assertThat(agents.findById(agent.getId())).isPresent();
            } finally {
                blocker.rollback();
                workers.shutdownNow();
            }
        }
    }

    @Test
    void shouldRejectConcurrentCreationAfterUnregistrationCommits() throws Exception {
        Agent agent = persistAgent("https://deletion-first.agent.test");
        // Hold skill cleanup so the real unregister transaction retains its parent lock.
        try (Connection blocker = dataSource.getConnection();
             ExecutorService workers = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            int blockerPid = backendPid(blocker);
            try (var statement = blocker.createStatement()) {
                statement.execute("LOCK TABLE agent_skills IN SHARE MODE");
            }
            try {
                var deletion = CompletableFuture.runAsync(() -> registry.unregister(agent.getId()), workers);
                int deletionPid = awaitBlockedQuery("delete from agent_skills", blockerPid);
                var creation = CompletableFuture.supplyAsync(() -> persistence.createSubmittedTask(
                        agent, "deletion-first", Map.of("query", "concurrent")), workers);
                awaitBlockedQuery("agents", deletionPid);

                blocker.commit();
                deletion.get(10, SECONDS);
                assertThatThrownBy(() -> creation.get(10, SECONDS))
                        .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(AgentNotFoundException.class);
                assertThat(agents.findById(agent.getId())).isEmpty();
                assertThat(taskRepository.count()).isZero();
            } finally {
                blocker.rollback();
                workers.shutdownNow();
            }
        }
    }

    private Agent persistAgent(String url) {
        return transaction.execute(status -> {
            Agent agent = new Agent();
            agent.setName("OriginalAgent");
            agent.setUrl(url);
            agent.setStatus("HEALTHY");
            agent.setAgentCard(new AgentCard("OriginalAgent", "test", url, "1.0.0", List.of(), Map.of(), List.of()));
            Agent saved = agents.saveAndFlush(agent);
            AgentSkillEntity skill = new AgentSkillEntity();
            skill.setAgent(saved);
            skill.setSkillId("test");
            skill.setName("Test skill");
            skill.setTags(List.of("test"));
            skills.saveAndFlush(skill);
            healthChecks.saveAndFlush(new HealthCheckLog(saved, "HEALTHY", 10, null));
            return saved;
        });
    }

    private Long taskVersion(UUID taskId) {
        return jdbc.queryForObject("SELECT lock_version FROM tasks WHERE id = ?", Long.class, taskId);
    }

    private Map<String, List<Map<String, Object>>> databaseSnapshot() {
        Map<String, List<Map<String, Object>>> snapshot = new LinkedHashMap<>();
        for (String table : List.of("agents", "agent_skills", "health_checks", "tasks")) {
            snapshot.put(table, jdbc.queryForList("SELECT to_jsonb(r)::text AS data FROM " + table + " r ORDER BY id"));
        }
        return snapshot;
    }

    private int backendPid(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT pg_backend_pid()")) {
            result.next();
            return result.getInt(1);
        }
    }

    private int awaitBlockedQuery(String queryFragment, int blockerPid) {
        AtomicInteger pid = new AtomicInteger();
        await().atMost(Duration.ofSeconds(15)).until(() -> {
            List<Integer> blocked = jdbc.queryForList("""
                    SELECT pid FROM pg_stat_activity
                    WHERE datname = current_database() AND pid <> pg_backend_pid()
                      AND wait_event_type = 'Lock' AND query LIKE ?
                      AND ? = ANY(pg_blocking_pids(pid))
                    """, Integer.class, "%" + queryFragment + "%", blockerPid);
            if (blocked.isEmpty()) {
                return false;
            }
            pid.set(blocked.getFirst());
            return true;
        });
        return pid.get();
    }
}
