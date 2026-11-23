package dev.a2ahub.health;

import dev.a2ahub.agent.Agent;
import dev.a2ahub.agent.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(AgentHealthServiceTransactionTest.Config.class)
class AgentHealthServiceTransactionTest {

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({AgentHealthService.class, HealthProperties.class})
    static class Config {
        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }

    @MockitoBean
    private DataSource dataSource;

    @MockitoBean
    private AgentRepository agentRepository;

    @MockitoBean
    private HealthCheckRepository healthCheckRepository;

    @MockitoBean
    private AgentHealthMonitor healthMonitor;

    @Autowired
    private AgentHealthService healthService;

    @Test
    void shouldProbeWithoutHoldingAServiceTransactionOrConnection() throws SQLException {
        UUID agentId = UUID.randomUUID();
        Agent agent = new Agent();
        agent.setId(agentId);
        var result = new AgentHealthMonitor.HealthCheckResult(agentId, "HEALTHY", 25, null);

        when(dataSource.getConnection()).thenReturn(mock(Connection.class));
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(healthMonitor.checkAgent(agent)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.hasResource(dataSource))
                    .as("probe should have no bound database connection")
                    .isFalse();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return result;
        });

        assertThat(healthService.triggerHealthCheck(agentId)).isEqualTo(result);
    }
}
