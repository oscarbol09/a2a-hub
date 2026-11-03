package dev.a2ahub.task;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class TaskHistoryMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("a2ahub_migration_test").withUsername("test").withPassword("test");

    private JdbcTemplate jdbc;
    private UUID agentId;
    private UUID taskId;
    private static final Instant ORIGINAL_TIME = Instant.parse("2020-01-01T00:00:00Z");

    @BeforeEach
    void populateV5Database() {
        // This fixture owns a dedicated ephemeral container, never an application database.
        Flyway v5 = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .cleanDisabled(false).target("5").load();
        v5.clean();
        v5.migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        agentId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, name, url, agent_card) VALUES (?, ?, ?, '{}'::jsonb)",
                agentId, "LegacyAgent", "https://legacy.agent.test");
        for (TaskState state : TaskState.values()) {
            UUID id = state == TaskState.COMPLETED ? taskId : UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO tasks (id, agent_id, context_id, state, request, response, error_detail,
                                       created_at, updated_at, lock_version)
                    VALUES (?, ?, 'legacy-context', ?, '{"query":"legacy"}'::jsonb,
                            '{"result":"retained"}'::jsonb, 'legacy-detail', ?, ?, 7)
                    """, id, agentId, state.name(), Timestamp.from(ORIGINAL_TIME), Timestamp.from(ORIGINAL_TIME));
        }
    }

    @Test
    void shouldUpgradePopulatedV5AndKeepExistingTimestampSemantics() {
        List<Map<String, Object>> originalExecutionData = executionData();
        migrateV6();

        assertThat(executionData()).isEqualTo(originalExecutionData);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tasks WHERE original_agent_id = ? AND original_agent_name = ?",
                Long.class, agentId, "LegacyAgent")).isEqualTo((long) TaskState.values().length);
        Instant afterBackfill = updatedAt();
        assertThat(afterBackfill).isAfter(ORIGINAL_TIME);
        assertThat(jdbc.queryForObject("""
                SELECT confdeltype::text FROM pg_constraint
                WHERE conrelid = 'tasks'::regclass AND conname = 'tasks_agent_id_fkey'
                """, String.class)).isEqualTo("n");
        assertThat(jdbc.queryForMap("""
                SELECT
                  (SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'public'
                   AND table_name = 'tasks' AND column_name = 'agent_id') AS live_nullable,
                  (SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'public'
                   AND table_name = 'tasks' AND column_name = 'original_agent_id') AS id_nullable,
                  (SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'public'
                   AND table_name = 'tasks' AND column_name = 'original_agent_name') AS name_nullable
                """)).containsEntry("live_nullable", "YES").containsEntry("id_nullable", "NO")
                .containsEntry("name_nullable", "NO");
        assertThat(jdbc.queryForObject("""
                SELECT indexdef FROM pg_indexes WHERE schemaname = 'public'
                  AND indexname = 'idx_tasks_original_agent_keyset'
                """, String.class)).contains("(original_agent_id, id DESC)");

        // Exercise the FK itself, independently of the application's active-task guard.
        jdbc.update("DELETE FROM agents WHERE id = ?", agentId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tasks WHERE agent_id IS NULL", Long.class))
                .isEqualTo((long) TaskState.values().length);
        assertThat(executionData()).isEqualTo(originalExecutionData);
        assertThat(updatedAt()).isAfter(afterBackfill);

        Instant afterDetachment = updatedAt();
        jdbc.update("UPDATE tasks SET state = state WHERE id = ?", taskId);
        assertThat(updatedAt()).isEqualTo(afterDetachment);
        jdbc.update("UPDATE tasks SET response = '{\"result\":\"changed\"}'::jsonb WHERE id = ?", taskId);
        assertThat(updatedAt()).isAfter(afterDetachment);
        assertThat(jdbc.queryForObject("SELECT lock_version FROM tasks WHERE id = ?", Long.class, taskId)).isEqualTo(7L);
    }

    @Test
    void shouldRollbackAnEntireFailedV6Migration() {
        // Force failure at V6's last statement, after its backfill and FK changes.
        jdbc.execute("CREATE INDEX idx_tasks_original_agent_keyset ON tasks (id)");
        List<Map<String, Object>> before = jdbc.queryForList("SELECT * FROM tasks ORDER BY id");

        assertThatThrownBy(this::migrateV6).isInstanceOf(FlywayException.class);

        assertThat(jdbc.queryForList("SELECT * FROM tasks ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public'
                  AND table_name = 'tasks' AND column_name IN ('original_agent_id', 'original_agent_name')
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT confdeltype::text FROM pg_constraint
                WHERE conrelid = 'tasks'::regclass AND conname = 'tasks_agent_id_fkey'
                """, String.class)).isEqualTo("r");
        assertThat(jdbc.queryForObject("SELECT max(version::integer) FROM flyway_schema_history WHERE success",
                Integer.class)).isEqualTo(5);
    }

    private void migrateV6() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .target("6").load().migrate();
    }

    private Instant updatedAt() {
        return jdbc.queryForObject("SELECT updated_at FROM tasks WHERE id = ?", Timestamp.class, taskId).toInstant();
    }

    private List<Map<String, Object>> executionData() {
        return jdbc.queryForList("""
                SELECT id, context_id, state, request, response, error_detail, created_at, lock_version
                FROM tasks ORDER BY id
                """);
    }
}
