package dev.a2ahub.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterAgentRequest(
    @NotBlank
    @Size(max = 512, message = "URL must not exceed 512 characters")
    @Pattern(regexp = "^https?://.*", message = "URL must start with http:// or https://")
    String url
) {}
