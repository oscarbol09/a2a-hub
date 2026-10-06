package dev.a2ahub;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("A2AHubApplication Entry Point Tests")
class A2AHubApplicationTest {

    @Test
    @DisplayName("Should instantiate application class and have required annotations")
    void shouldHaveRequiredAnnotations() {
        A2AHubApplication app = new A2AHubApplication();
        assertThat(app).isNotNull();

        assertThat(A2AHubApplication.class.isAnnotationPresent(SpringBootApplication.class)).isTrue();
        assertThat(A2AHubApplication.class.isAnnotationPresent(EnableScheduling.class)).isTrue();
    }
}
