package dev.a2ahub.api;

public class AiSuggestionRequest {
    private String taskDescription;

    public AiSuggestionRequest() {}

    public AiSuggestionRequest(String taskDescription) {
        this.taskDescription = taskDescription;
    }

    public String getTaskDescription() {
        return taskDescription;
    }

    public void setTaskDescription(String taskDescription) {
        this.taskDescription = taskDescription;
    }
}
