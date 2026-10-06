package dev.a2ahub.health;

import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(prefix = "a2ahub.health")
@Getter
@Setter
@Validated
public class HealthProperties {
    @Positive private long intervalMs = 30000;
    @Positive private int retentionHours = 48;
}
