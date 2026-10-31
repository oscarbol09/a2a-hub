package dev.a2ahub.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MDC task decorator tests")
class MdcTaskDecoratorTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldPropagateAndRestoreContext() {
        MDC.put("correlationId", "corr-123");
        AtomicReference<String> observed = new AtomicReference<>();

        Runnable decorated = new MdcTaskDecorator().decorate(() -> observed.set(MDC.get("correlationId")));
        MDC.clear();
        decorated.run();

        assertThat(observed).hasValue("corr-123");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
}
