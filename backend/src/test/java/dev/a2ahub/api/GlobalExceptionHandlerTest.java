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
        assertThat(problem.getTitle()).isEqualTo("Not Found");
    }

    @Test
    @DisplayName("Should translate IllegalArgumentException to RFC 7807 ProblemDetail with 400 Bad Request")
    void shouldHandleIllegalArgument() {
        IllegalArgumentException ex = new IllegalArgumentException("Invalid URI scheme provided");

        ProblemDetail problem = exceptionHandler.handleIllegalArgument(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getDetail()).isEqualTo("Invalid URI scheme provided");
        assertThat(problem.getTitle()).isEqualTo("Bad Request");
        assertThat(problem.getProperties()).containsEntry("error", "Bad Request");
    }

    @Test
    @DisplayName("Should translate IllegalStateException to RFC 7807 ProblemDetail with 409 Conflict")
    void shouldHandleIllegalState() {
        IllegalStateException ex = new IllegalStateException("Task is already in terminal COMPLETED state");

        ProblemDetail problem = exceptionHandler.handleIllegalState(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getDetail()).isEqualTo("Task is already in terminal COMPLETED state");
        assertThat(problem.getTitle()).isEqualTo("Conflict");
        assertThat(problem.getProperties()).containsEntry("error", "Conflict");
    }

    @Test
    @DisplayName("Should translate MethodArgumentNotValidException to structured field error ProblemDetail")
    void shouldHandleValidationExceptions() throws NoSuchMethodException {
        Object target = new Object();
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(target, "registerAgentRequest");
        bindingResult.addError(new FieldError("registerAgentRequest", "url", "must not be blank"));
        bindingResult.addError(new FieldError("registerAgentRequest", "name", "size must be between 1 and 100"));

        MethodParameter parameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("dummyMethod", String.class), 0
        );
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, bindingResult);

        ProblemDetail problem = exceptionHandler.handleValidationExceptions(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Validation Failed");
        assertThat(problem.getProperties()).containsKey("details");

        @SuppressWarnings("unchecked")
        Map<String, String> details = (Map<String, String>) problem.getProperties().get("details");
        assertThat(details)
                .containsEntry("url", "must not be blank")
                .containsEntry("name", "size must be between 1 and 100");
    }

    @Test
    @DisplayName("Should translate generic unhandled exceptions to 500 Internal Server Error ProblemDetail")
    void shouldHandleGenericException() {
        Exception ex = new RuntimeException("Database disk failure");

        ProblemDetail problem = exceptionHandler.handleGenericException(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getTitle()).isEqualTo("Internal Server Error");
        assertThat(problem.getDetail()).isEqualTo("An unexpected internal server error occurred.");
    }

    @SuppressWarnings("unused")
    private void dummyMethod(String param) {}
}
