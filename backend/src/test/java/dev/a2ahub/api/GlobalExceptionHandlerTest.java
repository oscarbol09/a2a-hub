package dev.a2ahub.api;

import dev.a2ahub.agent.AgentNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GlobalExceptionHandler Unit Tests")
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler exceptionHandler;

    @BeforeEach
    void setUp() {
        exceptionHandler = new GlobalExceptionHandler();
    }

    @Test
    @DisplayName("Should translate AgentNotFoundException to RFC 7807 ProblemDetail with 404 NOT_FOUND")
    void shouldHandleAgentNotFound() {
        UUID agentId = UUID.randomUUID();
        AgentNotFoundException ex = new AgentNotFoundException("Agent not found with id: " + agentId);

        ProblemDetail problem = exceptionHandler.handleAgentNotFound(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getDetail()).isEqualTo("Agent not found with id: " + agentId);
    }

    @Test
    @DisplayName("Should translate IllegalArgumentException to 400 Bad Request error map")
    void shouldHandleIllegalArgument() {
        IllegalArgumentException ex = new IllegalArgumentException("Invalid URI scheme provided");

        Map<String, String> response = exceptionHandler.handleIllegalArgument(ex);

        assertThat(response)
                .containsEntry("error", "Bad Request")
                .containsEntry("message", "Invalid URI scheme provided");
    }

    @Test
    @DisplayName("Should translate IllegalStateException to 409 Conflict error map")
    void shouldHandleIllegalState() {
        IllegalStateException ex = new IllegalStateException("Task is already in terminal COMPLETED state");

        Map<String, String> response = exceptionHandler.handleIllegalState(ex);

        assertThat(response)
                .containsEntry("error", "Conflict")
                .containsEntry("message", "Task is already in terminal COMPLETED state");
    }

    @Test
    @DisplayName("Should translate MethodArgumentNotValidException to structured field error map")
    void shouldHandleValidationExceptions() throws NoSuchMethodException {
        Object target = new Object();
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(target, "registerAgentRequest");
        bindingResult.addError(new FieldError("registerAgentRequest", "url", "must not be blank"));
        bindingResult.addError(new FieldError("registerAgentRequest", "name", "size must be between 1 and 100"));

        MethodParameter parameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("dummyMethod", String.class), 0
        );
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, bindingResult);

        Map<String, Object> response = exceptionHandler.handleValidationExceptions(ex);

        assertThat(response).containsEntry("error", "Validation Failed");
        @SuppressWarnings("unchecked")
        Map<String, String> details = (Map<String, String>) response.get("details");
        assertThat(details)
                .containsEntry("url", "must not be blank")
                .containsEntry("name", "size must be between 1 and 100");
    }

    @SuppressWarnings("unused")
    private void dummyMethod(String param) {}
}
