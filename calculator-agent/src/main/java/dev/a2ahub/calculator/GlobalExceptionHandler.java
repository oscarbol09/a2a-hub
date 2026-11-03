package dev.a2ahub.calculator;

import dev.a2ahub.calculator.dto.TaskRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler extends RequestBodyAdviceAdapter {

    private static final String CONTEXT_ID = GlobalExceptionHandler.class.getName() + ".contextId";
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return TaskRequest.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
                                Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        // Capture body context before validation or controller invocation can fail.
        RequestContextHolder.currentRequestAttributes().setAttribute(CONTEXT_ID,
                ((TaskRequest) body).getEffectiveContextId(), RequestAttributes.SCOPE_REQUEST);
        return body;
    }

    private String contextId(WebRequest request) {
        String header = request.getHeader("X-Context-Id");
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        Object context = request.getAttribute(CONTEXT_ID, RequestAttributes.SCOPE_REQUEST);
        return context instanceof String value ? value : new TaskRequest().getEffectiveContextId();
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(MethodArgumentNotValidException ex, WebRequest request) {
        String errorMessages = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));

        if (errorMessages == null || errorMessages.isBlank()) {
            errorMessages = "Validation failed for request payload";
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "error");
        response.put("message", errorMessages);
        response.put("result", errorMessages);
        response.put("contextId", contextId(request));
        response.put("code", 400);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex, WebRequest request) {
        log.error("Unhandled exception in Calculator Agent", ex);
        String message = ex.getMessage() != null ? ex.getMessage() : "Internal server error";

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "error");
        response.put("message", message);
        response.put("result", message);
        response.put("contextId", contextId(request));
        response.put("code", 500);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
