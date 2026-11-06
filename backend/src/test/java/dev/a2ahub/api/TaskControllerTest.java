package dev.a2ahub.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.a2ahub.agent.AgentNotFoundException;
import dev.a2ahub.config.WebConfig;
import dev.a2ahub.security.ApiKeyAuthFilter;
import dev.a2ahub.security.SecurityProperties;
import dev.a2ahub.task.TaskService;
import dev.a2ahub.task.TaskState;
import dev.a2ahub.task.TaskSummaryDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TaskController.class)
@Import({WebConfig.class, ApiKeyAuthFilter.class, SecurityProperties.class})
@DisplayName("TaskController Unit Tests")
class TaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TaskService taskService;

    @Test
    @DisplayName("POST /api/v1/tasks - Should submit task synchronously by default")
    void shouldSubmitTaskSynchronously() throws Exception {
        UUID agentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskService.SubmitTaskRequest request = new TaskService.SubmitTaskRequest(
                agentId,
                "ctx-123",
                Map.of("message", "hello agent")
        );

        TaskService.TaskDto taskDto = new TaskService.TaskDto(
                taskId,
                agentId,
                "EchoAgent",
                "ctx-123",
                "COMPLETED",
                Map.of("message", "hello agent"),
                Map.of("reply", "hello caller"),
                null,
                ZonedDateTime.now(),
                ZonedDateTime.now()
        );

        when(taskService.submitTask(any(TaskService.SubmitTaskRequest.class), eq(false)))
                .thenReturn(new TaskService.TaskExecutionResponse(taskDto, false));

        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(taskId.toString()))
                .andExpect(jsonPath("$.agentName").value("EchoAgent"))
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.contextId").value("ctx-123"));

        verify(taskService).submitTask(any(TaskService.SubmitTaskRequest.class), eq(false));
    }

    @Test
    @DisplayName("POST /api/v1/tasks - Should return 202 Accepted when Prefer: respond-async is provided")
    void shouldReturn202WhenPreferAsyncProvided() throws Exception {
        UUID agentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskService.SubmitTaskRequest request = new TaskService.SubmitTaskRequest(
                agentId,
                "ctx-async-1",
                Map.of("prompt", "deep thinking")
        );

        TaskService.TaskDto submittedDto = new TaskService.TaskDto(
                taskId,
                agentId,
                "EchoAgent",
                "ctx-async-1",
                "SUBMITTED",
                Map.of("prompt", "deep thinking"),
                null,
                null,
                ZonedDateTime.now(),
                ZonedDateTime.now()
        );

        when(taskService.submitTask(any(TaskService.SubmitTaskRequest.class), eq(true)))
                .thenReturn(new TaskService.TaskExecutionResponse(submittedDto, true));

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Prefer", "respond-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Preference-Applied", "respond-async"))
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/tasks/" + taskId))
                .andExpect(jsonPath("$.id").value(taskId.toString()))
                .andExpect(jsonPath("$.state").value("SUBMITTED"));

        verify(taskService).submitTask(any(TaskService.SubmitTaskRequest.class), eq(true));
    }

    @Test
    @DisplayName("POST /api/v1/tasks - Should return 404 ProblemDetail when agent not found")
    void shouldReturn404WhenSubmittingTaskToMissingAgent() throws Exception {
        UUID agentId = UUID.randomUUID();
        TaskService.SubmitTaskRequest request = new TaskService.SubmitTaskRequest(
                agentId,
                "ctx-missing-agent",
                Map.of("message", "hello agent")
        );

        when(taskService.submitTask(any(TaskService.SubmitTaskRequest.class), anyBoolean()))
                .thenThrow(new AgentNotFoundException("Agent not found with ID: " + agentId));

        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value("Agent not found with ID: " + agentId));
    }

    @Test
    @DisplayName("POST /api/v1/tasks - Should return 400 when contextId exceeds 255 characters")
    void shouldReturn400WhenContextIdTooLong() throws Exception {
        TaskService.SubmitTaskRequest request = new TaskService.SubmitTaskRequest(
                UUID.randomUUID(),
                "c".repeat(256),
                Map.of("message", "hello agent")
        );

        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.details.contextId").value("contextId must not exceed 255 characters"));

        verify(taskService, never()).submitTask(any(TaskService.SubmitTaskRequest.class), anyBoolean());
    }

    @Test
    @DisplayName("POST /api/v1/tasks - Should accept contextId of exactly 255 characters")
    void shouldAcceptContextIdAtMaxLength() throws Exception {
        UUID agentId = UUID.randomUUID();
        String contextId = "c".repeat(255);
        TaskService.SubmitTaskRequest request = new TaskService.SubmitTaskRequest(
                agentId,
                contextId,
                Map.of("message", "hello agent")
        );

        TaskService.TaskDto taskDto = new TaskService.TaskDto(
                UUID.randomUUID(),
                agentId,
                "EchoAgent",
                contextId,
                "COMPLETED",
                Map.of("message", "hello agent"),
                Map.of("reply", "hello caller"),
                null,
                ZonedDateTime.now(),
                ZonedDateTime.now()
        );

        when(taskService.submitTask(any(TaskService.SubmitTaskRequest.class), eq(false)))
                .thenReturn(new TaskService.TaskExecutionResponse(taskDto, false));

        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contextId").value(contextId));
    }

    @Test
    @DisplayName("POST /api/v1/tasks - Should still accept a missing contextId")
    void shouldAcceptMissingContextId() throws Exception {
        UUID agentId = UUID.randomUUID();
        TaskService.SubmitTaskRequest request = new TaskService.SubmitTaskRequest(
                agentId,
                null,
                Map.of("message", "hello agent")
        );

        TaskService.TaskDto taskDto = new TaskService.TaskDto(
                UUID.randomUUID(),
                agentId,
                "EchoAgent",
                UUID.randomUUID().toString(),
                "COMPLETED",
                Map.of("message", "hello agent"),
                Map.of("reply", "hello caller"),
                null,
                ZonedDateTime.now(),
                ZonedDateTime.now()
        );

        when(taskService.submitTask(any(TaskService.SubmitTaskRequest.class), eq(false)))
                .thenReturn(new TaskService.TaskExecutionResponse(taskDto, false));

        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("GET /api/v1/tasks/{id} - Should return task by id")
    void shouldGetTaskById() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();

        TaskService.TaskDto taskDto = new TaskService.TaskDto(
                taskId,
                agentId,
                "EchoAgent",
                "ctx-999",
                "COMPLETED",
                Map.of("action", "ping"),
                Map.of("pong", true),
                null,
                ZonedDateTime.now(),
                ZonedDateTime.now()
        );

        when(taskService.findById(taskId)).thenReturn(taskDto);

        mockMvc.perform(get("/api/v1/tasks/{id}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(taskId.toString()))
                .andExpect(jsonPath("$.state").value("COMPLETED"));

        verify(taskService).findById(taskId);
    }

    @Test
    @DisplayName("GET /api/v1/tasks/agent/{agentId} - Should return tasks for agent")
    void shouldGetTasksForAgent() throws Exception {
        UUID agentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        TaskService.TaskDto taskDto = new TaskService.TaskDto(
                taskId,
                agentId,
                "EchoAgent",
                "ctx-abc",
                "WORKING",
                Map.of("task", "stream"),
                null,
                null,
                ZonedDateTime.now(),
                ZonedDateTime.now()
        );

        when(taskService.findByAgentId(agentId)).thenReturn(List.of(taskDto));

        mockMvc.perform(get("/api/v1/tasks/agent/{agentId}", agentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(taskId.toString()))
                .andExpect(jsonPath("$[0].state").value("WORKING"));

        verify(taskService).findByAgentId(agentId);
    }

    @Test
    @DisplayName("GET /api/v1/tasks/agent/{agentId}?cursorId=...&size=... - Should return keyset paginated slice of task summaries")
    void shouldGetTasksForAgentWithKeysetPagination() throws Exception {
        UUID agentId = UUID.randomUUID();
        UUID cursorId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        TaskSummaryDto summary = new TaskSummaryDto(
                taskId,
                agentId,
                "EchoAgent",
                "ctx-keyset-1",
                TaskState.COMPLETED,
                null,
                ZonedDateTime.now(),
                ZonedDateTime.now()
        );

        Slice<TaskSummaryDto> slice = new SliceImpl<>(List.of(summary), PageRequest.of(0, 10), false);

        when(taskService.findSummariesByAgentId(agentId, cursorId, 10)).thenReturn(slice);

        mockMvc.perform(get("/api/v1/tasks/agent/{agentId}", agentId)
                        .param("cursorId", cursorId.toString())
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(taskId.toString()))
                .andExpect(jsonPath("$.content[0].agentName").value("EchoAgent"))
                .andExpect(jsonPath("$.content[0].state").value("COMPLETED"))
                .andExpect(jsonPath("$.content[0].contextId").value("ctx-keyset-1"))
                .andExpect(jsonPath("$.numberOfElements").value(1));

        verify(taskService).findSummariesByAgentId(agentId, cursorId, 10);
    }
}
