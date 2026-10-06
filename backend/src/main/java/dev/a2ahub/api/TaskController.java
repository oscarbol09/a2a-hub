package dev.a2ahub.api;

import dev.a2ahub.task.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskService.TaskDto submitTask(@RequestBody @Valid TaskService.SubmitTaskRequest request) {
        return taskService.submitTask(request);
    }

    @GetMapping("/{id}")
    public TaskService.TaskDto getTask(@PathVariable UUID id) {
        return taskService.findById(id);
    }

    @GetMapping("/agent/{agentId}")
    public List<TaskService.TaskDto> getTasksByAgent(@PathVariable UUID agentId) {
        return taskService.findByAgentId(agentId);
    }
}
