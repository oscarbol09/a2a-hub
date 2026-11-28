package dev.a2ahub.api;

import dev.a2ahub.task.TaskService;
import dev.a2ahub.task.TaskSummaryDto;
import jakarta.validation.Valid;
import org.springframework.data.domain.Slice;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    /**
     * Submits a task.
     * Supports RFC 7240: If client passes `Prefer: respond-async` (or ?async=true), responds immediately with 202 Accepted.
     */
    @PostMapping
    public ResponseEntity<TaskService.TaskDto> submitTask(
            @RequestBody @Valid TaskService.SubmitTaskRequest request,
            @RequestHeader(value = "Prefer", required = false) String preferHeader,
            @RequestParam(value = "async", required = false, defaultValue = "false") boolean asyncParam
    ) {
        boolean preferAsync = (preferHeader != null && preferHeader.contains("respond-async"))
                || asyncParam
                || Boolean.TRUE.equals(request.async());

        TaskService.TaskExecutionResponse response = taskService.submitTask(request, preferAsync);

        if (response.isAsync()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .header(HttpHeaders.LOCATION, "/api/v1/tasks/" + response.task().id())
                    .header("Preference-Applied", "respond-async")
                    .body(response.task());
        } else {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(response.task());
        }
    }

    @GetMapping("/{id}")
    public TaskService.TaskDto getTask(@PathVariable UUID id) {
        return taskService.findById(id);
    }

    /**
     * Agent tasks endpoint.
     * Supports O(1) Keyset cursor pagination (?cursorId=...&size=...) bypassing heavy TOAST payloads.
     * Defaults to bounded list of latest tasks for backward compatibility.
     */
    @GetMapping("/agent/{agentId}")
    public ResponseEntity<?> getTasksByAgent(
            @PathVariable UUID agentId,
            @RequestParam(value = "cursorId", required = false) UUID cursorId,
            @RequestParam(value = "size", required = false) Integer size
    ) {
        if (cursorId != null || size != null) {
            int pageSize = size != null ? size : 20;
            Slice<TaskSummaryDto> slice = taskService.findSummariesByAgentId(agentId, cursorId, pageSize);
            return ResponseEntity.ok(slice);
        }

        List<TaskService.TaskDto> list = taskService.findByAgentId(agentId);
        return ResponseEntity.ok(list);
    }
}
