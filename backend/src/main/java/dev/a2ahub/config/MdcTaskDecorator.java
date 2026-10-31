package dev.a2ahub.config;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/** Copies MDC values into asynchronous tasks and restores the worker thread afterward. */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> capturedContext = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previousContext = MDC.getCopyOfContextMap();
            try {
                MDC.clear();
                if (capturedContext != null) {
                    MDC.setContextMap(capturedContext);
                }
                runnable.run();
            } finally {
                MDC.clear();
                if (previousContext != null) {
                    MDC.setContextMap(previousContext);
                }
            }
        };
    }
}
