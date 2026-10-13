package dev.a2ahub.health;

import dev.a2ahub.agent.Agent;
import dev.a2ahub.agent.AgentCard;
import dev.a2ahub.agent.AgentRepository;
import dev.a2ahub.security.SsrfValidator;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentHealthMonitor Unit Tests")
class AgentHealthMonitorTest {

    private static final int RETENTION_HOURS = 48;

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private HealthCheckRepository healthCheckRepository;

    @Mock
    private AgentHealthPersistenceService persistenceService;

    @Mock
    private SsrfValidator ssrfValidator;

    @Mock
    private RestClient.Builder restClientBuilder;

    @Mock
    private RestClient probeClient;

    @Mock
    @SuppressWarnings("rawtypes")
    private RestClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    private AgentHealthMonitor healthMonitor;

    @BeforeEach
    void setUp() {
        when(restClientBuilder.requestFactory(any())).thenReturn(restClientBuilder);
        when(restClientBuilder.build()).thenReturn(probeClient);
        HealthProperties healthProperties = new HealthProperties();
        healthProperties.setRetentionHours(RETENTION_HOURS);

        healthMonitor = new AgentHealthMonitor(
                agentRepository,
                healthCheckRepository,
                persistenceService,
                ssrfValidator,
                restClientBuilder,
                healthProperties,
                new SimpleAsyncTaskExecutor()
        );
    }

    @Test
    @DisplayName("Should mark agent HEALTHY on valid Agent Card response and delegate persistence")
    @SuppressWarnings("unchecked")
    void shouldMarkAgentHealthyOnValidResponse() {
        UUID agentId = UUID.randomUUID();
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setName("Translator");
        agent.setUrl("https://translator.agent.io");
        agent.setStatus("UNKNOWN");

        AgentCard card = new AgentCard("Translator", "Translation agent", "https://translator.agent.io", "1.0", List.of(), Map.of(), List.of());

        when(probeClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(URI.class))).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(AgentCard.class)).thenReturn(card);

        AgentHealthMonitor.HealthCheckResult result = healthMonitor.checkAgent(agent);

        assertThat(result.status()).isEqualTo("HEALTHY");
        assertThat(result.error()).isNull();
        verify(persistenceService).recordProbeResult(eq(agentId), eq("HEALTHY"), anyInt(), isNull());
    }

    @Test
    @DisplayName("Should mark agent OFFLINE when connection fails and record error")
    @SuppressWarnings("unchecked")
    void shouldMarkAgentOfflineOnConnectionError() {
        UUID agentId = UUID.randomUUID();
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setName("BrokenAgent");
        agent.setUrl("https://broken.agent.io");
        agent.setStatus("HEALTHY");

        when(probeClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(URI.class))).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.retrieve()).thenThrow(new RuntimeException("Connection refused"));

        AgentHealthMonitor.HealthCheckResult result = healthMonitor.checkAgent(agent);

        assertThat(result.status()).isEqualTo("OFFLINE");
        assertThat(result.error()).contains("Connection refused");
        verify(persistenceService).recordProbeResult(eq(agentId), eq("OFFLINE"), anyInt(), contains("Connection refused"));
    }

    @Test
    @DisplayName("Should execute chunked batch pruning of health check logs")
    void shouldPurgeOldHealthLogsInBatches() {
        when(healthCheckRepository.deleteBatchOlderThan(any(ZonedDateTime.class), anyInt()))
                .thenReturn(5000)
                .thenReturn(120);

        ZonedDateTime before = ZonedDateTime.now();

        healthMonitor.purgeOldHealthLogs();

        ArgumentCaptor<ZonedDateTime> cutoff = ArgumentCaptor.forClass(ZonedDateTime.class);
        verify(healthCheckRepository, times(2)).deleteBatchOlderThan(cutoff.capture(), eq(5000));
        assertThat(cutoff.getValue())
                .isBetween(before.minusHours(RETENTION_HOURS), ZonedDateTime.now().minusHours(RETENTION_HOURS));
    }

    @Test
    @DisplayName("Should throttle concurrent probes via semaphore permits")
    @SuppressWarnings("unchecked")
    void shouldThrottleConcurrentProbesViaSemaphore() throws Exception {
        HealthProperties props = new HealthProperties();
        props.setMaxConcurrentProbes(2);

        AgentHealthMonitor throttledMonitor = new AgentHealthMonitor(
                agentRepository,
                healthCheckRepository,
                persistenceService,
                ssrfValidator,
                restClientBuilder,
                props,
                new SimpleAsyncTaskExecutor()
        );

        assertThat(throttledMonitor.getProbeSemaphore().availablePermits()).isEqualTo(2);

        CountDownLatch probeStartedLatch = new CountDownLatch(1);
        CountDownLatch unblockLatch = new CountDownLatch(1);

        UUID agentId = UUID.randomUUID();
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setName("SlowAgent");
        agent.setUrl("https://slow.agent.io");

        when(probeClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(URI.class))).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.retrieve()).thenAnswer(invocation -> {
            probeStartedLatch.countDown();
            unblockLatch.await(5, TimeUnit.SECONDS);
            return responseSpec;
        });
        AgentCard card = new AgentCard("SlowAgent", "Slow", "https://slow.agent.io", "1.0", List.of(), Map.of(), List.of());
        when(responseSpec.body(AgentCard.class)).thenReturn(card);

        CompletableFuture<AgentHealthMonitor.HealthCheckResult> future = CompletableFuture.supplyAsync(
                () -> throttledMonitor.checkAgent(agent)
        );

        assertThat(probeStartedLatch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(throttledMonitor.getProbeSemaphore().availablePermits()).isEqualTo(1);

        unblockLatch.countDown();
        AgentHealthMonitor.HealthCheckResult result = future.get(5, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo("HEALTHY");
        assertThat(throttledMonitor.getProbeSemaphore().availablePermits()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should trip circuit breaker to OPEN after repeated failures and fast-fail without network I/O")
    @SuppressWarnings("unchecked")
    void shouldTripCircuitBreakerToOpenAndFastFail() {
        UUID agentId = UUID.randomUUID();
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setName("FlakyAgent");
        agent.setUrl("https://flaky.agent.io");

        when(probeClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(URI.class))).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.retrieve()).thenThrow(new RuntimeException("Connection refused"));

        // Execute 3 failing calls (minimumNumberOfCalls = 3, failureRateThreshold = 50.0%)
        for (int i = 0; i < 3; i++) {
            AgentHealthMonitor.HealthCheckResult r = healthMonitor.checkAgent(agent);
            assertThat(r.status()).isEqualTo("OFFLINE");
        }

        // Verify circuit breaker state for host "flaky.agent.io" is OPEN
        CircuitBreaker cb = healthMonitor.getCircuitBreakerRegistry().circuitBreaker("flaky.agent.io");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Call 4: should fast-fail without executing network I/O
        AgentHealthMonitor.HealthCheckResult fastFailResult = healthMonitor.checkAgent(agent);

        assertThat(fastFailResult.status()).isEqualTo("OFFLINE");
        assertThat(fastFailResult.latencyMs()).isNull();
        assertThat(fastFailResult.error())
                .isEqualTo("Circuit breaker OPEN: agent host flaky.agent.io is repeatedly failing, backing off");

        // Verify probeClient.get() was only called 3 times total, NOT 4 times (no network I/O on 4th call)
        verify(probeClient, times(3)).get();

        // Verify persistence service recorded all 4 checks, including the fast-fail probe
        verify(persistenceService, times(4)).recordProbeResult(eq(agentId), eq("OFFLINE"), anyInt(), any());
    }

    @Test
    @DisplayName("Should record success on DEGRADED status keeping circuit breaker CLOSED")
    @SuppressWarnings("unchecked")
    void shouldRecordSuccessOnDegradedStatus() {
        UUID agentId = UUID.randomUUID();
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setName("DegradedAgent");
        agent.setUrl("https://degraded.agent.io");

        AgentCard cardWithoutName = new AgentCard(null, "Missing name", "https://degraded.agent.io", "1.0", List.of(), Map.of(), List.of());

        when(probeClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(URI.class))).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(AgentCard.class)).thenReturn(cardWithoutName);

        for (int i = 0; i < 3; i++) {
            AgentHealthMonitor.HealthCheckResult r = healthMonitor.checkAgent(agent);
            assertThat(r.status()).isEqualTo("DEGRADED");
        }

        CircuitBreaker cb = healthMonitor.getCircuitBreakerRegistry().circuitBreaker("degraded.agent.io");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
