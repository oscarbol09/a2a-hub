package dev.a2ahub.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.a2ahub.agent.AgentNotFoundException;
import dev.a2ahub.security.ApiKeyAuthFilter;
import dev.a2ahub.security.SecurityProperties;
import dev.a2ahub.task.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TaskController.class)
@Import({SecurityProperties.class, ApiKeyAuthFilter.class})
@DisplayName("TaskController Web Slice Tests")
class TaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TaskService taskService;

    @Test
    @DisplayName("POST /api/v1/tasks - Should submit task successfully")
    void shouldSubmitTask() throws Exception {
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

        when(taskService.submitTask(any(TaskService.SubmitTaskRequest.class))).thenReturn(taskDto);

        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(taskId.toString()))
                .andExpect(jsonPath("$.agentName").value("EchoAgent"))
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.contextId").value("ctx-123"));

        verify(taskService).submitTask(any(TaskService.SubmitTaskRequest.class));
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

        when(taskService.submitTask(any(TaskService.SubmitTaskRequest.class)))
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
}
