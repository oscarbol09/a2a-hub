package dev.a2ahub.task;

import dev.a2ahub.agent.Agent;
import dev.a2ahub.agent.AgentCard;
import dev.a2ahub.agent.AgentRepository;
import dev.a2ahub.events.AgentEventPublisher;
import dev.a2ahub.security.SecurityProperties;
import dev.a2ahub.security.SsrfValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.net.UnknownHostException;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TaskServiceEndpointTest {
    private final AgentRepository agents = mock(AgentRepository.class);
    private final TaskPersistenceService persistence = mock(TaskPersistenceService.class);
    private final SsrfValidator validator = mock(SsrfValidator.class);
    private final AgentEventPublisher publisher = mock(AgentEventPublisher.class);
    private final AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
    private final UUID taskId = UUID.randomUUID();
    private Agent agent;
    private TaskService service;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        agent = new Agent();
        agent.setName("EndpointAgent");
        agent.setUrl("https://registered.example/root");
        agent.setStatus("HEALTHY");
        when(agents.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(persistence.createSubmittedTask(eq(agent), any(), any()))
                .thenReturn(dto(TaskState.SUBMITTED, null, null));
        when(persistence.updateTaskResult(any(), any(), any(), any())).thenAnswer(call ->
                dto(call.getArgument(1), call.getArgument(2), call.getArgument(3)));
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new TaskService(mock(TaskRepository.class), agents, persistence, validator, publisher, builder, builder.build(), executor);
    }

    private TaskService.TaskDto dto(TaskState state, Map<String, Object> response, String error) {
        return new TaskService.TaskDto(taskId, agent.getId(), "EndpointAgent", "ctx-endpoint", state.name(),
                Map.of("query", "test"), response, error, ZonedDateTime.now(), ZonedDateTime.now());
    }

    private void interfaces(List<Object> interfaces) {
        agent.setAgentCard(new AgentCard("EndpointAgent", "test", null, "1.0", List.of(), Map.of(), interfaces));
    }

    private TaskService.TaskExecutionResponse submit(boolean async) {
        return service.submitTask(new TaskService.SubmitTaskRequest(agent.getId(), "ctx-endpoint", Map.of("query", "test")), async);
    }

    @Test
    void dispatchesFirstCompatibleEndpointAndValidatesBeforeAdmission() {
        String endpoint = "https://registered.example/api/tasks?mode=fast";
        interfaces(List.of(new AgentCard.AgentInterface("https://rpc.example", "JSON-RPC", "2.0"), Map.of(),
                Map.of("url", "  " + endpoint + "  ", "protocolBinding", " http+json "),
                Map.of("url", "https://registered.example/second")));
        server.expect(requestTo(endpoint)).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"query\":\"test\",\"contextId\":\"ctx-endpoint\"}"))
                .andExpect(header("X-Context-Id", "ctx-endpoint"))
                .andExpect(header("X-Task-Id", taskId.toString()))
                .andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));

        assertThat(submit(false).task().state()).isEqualTo("COMPLETED");
        var order = inOrder(validator, persistence);
        order.verify(validator).validateSafeRemoteUrl(agent.getUrl());
        order.verify(validator).validateSafeRemoteUrl(endpoint);
        order.verify(persistence).createSubmittedTask(eq(agent), any(), any());
        server.verify();
    }

    static Stream<Arguments> fallbacks() {
        return Stream.of(Arguments.of("https://registered.example/root", null),
                Arguments.of("https://registered.example/root/", List.of()),
                Arguments.of("https://registered.example/root", List.of("REST", "JSON-RPC")),
                Arguments.of("https://registered.example/root/", List.of(Map.of(), Map.of("url", " "))));
    }

    @ParameterizedTest
    @MethodSource("fallbacks")
    void preservesLegacyFallback(String base, List<Object> declarations) {
        agent.setUrl(base);
        if (declarations != null) interfaces(declarations);
        String expected = "https://registered.example/root/tasks";
        server.expect(requestTo(expected)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThat(submit(false).task().state()).isEqualTo("COMPLETED");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://other.example:8443/api/tasks", "https://registered.example:8443/api/tasks"})
    void permitsUnauthenticatedCrossOriginStringEndpointsWithoutHeaders(String endpoint) {
        agent.setAuthType("NONE");
        agent.setAuthTokenEnc("unused-token");
        interfaces(List.of(endpoint));
        server.expect(requestTo(endpoint)).andExpect(headerDoesNotExist("Authorization"))
                .andExpect(headerDoesNotExist("X-API-Key"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThat(submit(false).task().state()).isEqualTo("COMPLETED");
        verify(validator).validateSafeRemoteUrl(endpoint);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://registered.example/api/tasks", "HTTPS://REGISTERED.EXAMPLE:443/api/tasks"})
    void forwardsCredentialsOnlyToSameOriginIncludingDefaultPort(String endpoint) {
        interfaces(List.of(new AgentCard.AgentInterface(endpoint, null, "1.0")));
        agent.setAuthType("BEARER");
        agent.setAuthTokenEnc("Bearer original-token");
        server.expect(requestTo(endpoint)).andExpect(header("Authorization", "Bearer original-token"))
                .andExpect(headerDoesNotExist("X-API-Key"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        submit(false);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://registered.example/api/tasks", "http://REGISTERED.EXAMPLE:80/api/tasks"})
    void handlesApiKeyAndHttpDefaultPort(String endpoint) {
        if (endpoint.startsWith("http:")) agent.setUrl("http://registered.example/root");
        interfaces(List.of(endpoint));
        agent.setAuthType("API_KEY");
        agent.setAuthTokenEnc("api-key");
        server.expect(requestTo(endpoint)).andExpect(header("X-API-Key", "api-key"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        submit(false);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/relative/tasks", "https://[", "https://registered.example:0/tasks",
            "https://registered.example:65536/tasks", "https://registered.example:bad/tasks",
            "https://registered.example:/tasks", "https:///tasks", "//registered.example/tasks",
            "https://user:secret@registered.example/tasks", "https://registered.example/tasks#fragment",
            "file:///etc/passwd", "ftp://registered.example/tasks", "http://registered.example/tasks"})
    void rejectsInvalidEndpointBeforeTaskCreationOrScheduling(String endpoint) {
        interfaces(List.of(Map.of("url", endpoint), Map.of("url", "https://registered.example/valid")));
        assertRejected();
        verifyNoInteractions(validator);
    }

    @Test
    void doesNotFallbackForUnsupportedOnlyEndpoint() {
        interfaces(List.of(new AgentCard.AgentInterface("https://rpc.example", "JSON-RPC", "2.0")));
        assertRejected();
    }

    @Test
    void rejectsForbiddenSchemeStringInsteadOfTreatingItAsLegacyLabel() {
        interfaces(List.of("file:///etc/passwd"));
        assertRejected();
    }

    @ParameterizedTest
    @ValueSource(strings = {"//registered.example/tasks", " \t//registered.example/tasks \t"})
    void rejectsSchemeRelativeStringInsteadOfTreatingItAsLegacyLabel(String endpoint) {
        interfaces(List.of(endpoint));
        assertRejected();
        verifyNoInteractions(validator);
    }

    static Stream<Arguments> credentialOrigins() {
        return Stream.of("BEARER", "API_KEY").flatMap(auth -> Stream.of(
                Arguments.of(auth, "https://registered.example", "https://other.example/tasks"),
                Arguments.of(auth, "https://registered.example", "https://registered.example:8443/tasks"),
                Arguments.of(auth, "http://registered.example", "https://registered.example/tasks")));
    }

    @ParameterizedTest
    @MethodSource("credentialOrigins")
    void rejectsAuthenticatedCrossOriginBeforeDnsOrAdmission(String auth, String base, String endpoint) {
        agent.setUrl(base);
        agent.setAuthType(auth);
        agent.setAuthTokenEnc("secret-token");
        interfaces(List.of(endpoint));
        assertRejected();
        verifyNoInteractions(validator);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://127.0.0.1/tasks", "https://169.254.169.254/tasks", "https://[::1]/tasks",
            "https://10.0.0.1/tasks"})
    void validatesActualEndpointWithRealSsrfValidatorBeforeAdmission(String endpoint) {
        agent.setUrl("https://8.8.8.8");
        interfaces(List.of(endpoint));
        // IP literals keep this test independent of external DNS and connectivity.
        SsrfValidator realValidator = new SsrfValidator(new SecurityProperties());
        doAnswer(call -> { realValidator.validateSafeRemoteUrl(call.getArgument(0)); return null; })
                .when(validator).validateSafeRemoteUrl(any());
        assertRejected();
        verify(validator, times(2)).validateSafeRemoteUrl(endpoint);
    }

    private void assertRejected() {
        // Exercise both modes: neither may enqueue work or create a task on admission rejection.
        for (boolean async : List.of(false, true)) {
            assertThatThrownBy(() -> submit(async)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("secret-token").hasMessageNotContaining("user:secret");
        }
        verifyNoInteractions(persistence, executor, publisher);
        server.verify();
    }

    static Stream<Arguments> executionFailures() {
        return Stream.of(
                Arguments.of(withServerError(), "Agent returned HTTP 500"),
                Arguments.of(withSuccess("{\"status\":\"error\",\"result\":\"provider down\"}", MediaType.APPLICATION_JSON), "provider down"),
                Arguments.of(withException(new UnknownHostException("restricted address")), "Agent connection/read timeout:"));
    }

    @ParameterizedTest
    @MethodSource("executionFailures")
    void asyncUsesCapturedEndpointAndCredentialsAndPersistsTerminalFailure(ResponseCreator response, String detail) {
        String endpoint = "https://registered.example/custom/tasks";
        interfaces(List.of(endpoint));
        agent.setAuthType("BEARER");
        agent.setAuthTokenEnc("original-token");
        server.expect(requestTo(endpoint)).andExpect(header("Authorization", "Bearer original-token"))
                .andRespond(response);

        assertThat(submit(true).task().state()).isEqualTo("SUBMITTED");
        verify(persistence, never()).updateTaskResult(any(), any(), any(), any());
        ArgumentCaptor<Runnable> worker = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(worker.capture());
        interfaces(List.of("https://other.example/changed"));
        agent.setUrl("https://other.example");
        agent.setAuthType("API_KEY");
        agent.setAuthTokenEnc("changed-token");
        worker.getValue().run();

        var order = inOrder(persistence, publisher);
        order.verify(persistence).createSubmittedTask(eq(agent), any(), any());
        order.verify(persistence).updateTaskResult(taskId, TaskState.WORKING, null, null);
        order.verify(publisher).publishTaskUpdated(argThat(dto -> dto.state().equals("WORKING")));
        order.verify(persistence).updateTaskResult(eq(taskId), eq(TaskState.FAILED), any(), contains(detail));
        order.verify(publisher).publishTaskUpdated(argThat(dto -> dto.state().equals("FAILED") && dto.errorDetail().contains(detail)));
        server.verify();
    }

    @Test
    void preservesBothPublicProxyOverloadsAsBaseUrlCalls() {
        server.expect(requestTo("https://registered.example/root/tasks"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://registered.example/root/tasks"))
                .andExpect(header("X-API-Key", "api-key"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        service.executeDownstreamProxy(taskId, "ctx-endpoint", "EndpointAgent", agent.getUrl(), Map.of(), "corr", "trace");
        service.executeDownstreamProxy(taskId, "ctx-endpoint", "EndpointAgent", agent.getUrl() + "/", Map.of(), "corr", "trace", "API_KEY", "api-key");
        server.verify();
    }
}
