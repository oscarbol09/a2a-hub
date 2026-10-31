package dev.a2ahub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/**
 * Centralized Virtual Thread execution configuration.
 * Integrates into Spring's ApplicationContext lifecycle with graceful shutdown.
 */
@Configuration
public class VirtualThreadConfig {

    @Bean(name = "healthProbeTaskExecutor")
    public AsyncTaskExecutor healthProbeTaskExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("health-probe-worker-");
        executor.setVirtualThreads(true);
        executor.setTaskDecorator(new MdcTaskDecorator());
        return executor;
    }
}
