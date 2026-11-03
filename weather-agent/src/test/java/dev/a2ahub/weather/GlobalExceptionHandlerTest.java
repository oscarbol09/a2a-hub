package dev.a2ahub.weather;

import dev.a2ahub.weather.dto.TaskRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WeatherAgentController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    WeatherAgentController controller;

    @Test
    void validationErrorsKeepBodyContextAndLegacyFields() throws Exception {
        mvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + "x".repeat(501) + "\",\"contextId\":\" ctx-body \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.result", containsString("query must not exceed 500 characters")))
                .andExpect(jsonPath("$.message", containsString("query must not exceed 500 characters")))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.contextId").value("ctx-body"));
    }

    @Test
    void validationErrorsPreferHeaderContext() throws Exception {
        mvc.perform(post("/tasks").header("X-Context-Id", " ctx-header ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\" \",\"contextId\":\"ctx-body\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result", containsString("query must not be blank")))
                .andExpect(jsonPath("$.contextId").value("ctx-header"));
    }

    @Test
    void internalErrorsKeepBodyContextAndLegacyFields() throws Exception {
        when(controller.handleTask(any(TaskRequest.class), nullable(String.class)))
                .thenThrow(new IllegalStateException("synthetic internal failure"));
        mvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"synthetic\",\"contextId\":\"ctx-body\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.result").value("synthetic internal failure"))
                .andExpect(jsonPath("$.message").value("synthetic internal failure"))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.contextId").value("ctx-body"));
    }

    @Test
    void internalErrorsPreferHeaderAndHandleNullExceptionMessage() throws Exception {
        when(controller.handleTask(any(TaskRequest.class), nullable(String.class)))
                .thenThrow(new IllegalStateException());
        mvc.perform(post("/tasks").header("X-Context-Id", " ctx-header ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"synthetic\",\"contextId\":\"ctx-body\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.result").value("Internal server error"))
                .andExpect(jsonPath("$.contextId").value("ctx-header"));
    }

    @Test
    void missingContextUsesExistingAgentDefault() throws Exception {
        var response = mvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\" \"}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse();
        var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString());
        assertThat(java.util.UUID.fromString(body.get("contextId").asText())).isNotNull();
    }
}
