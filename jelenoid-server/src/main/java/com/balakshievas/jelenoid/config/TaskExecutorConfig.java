package com.balakshievas.jelenoid.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class TaskExecutorConfig {
    public static final String SESSION_TASK_EXECUTOR = "sessionTaskExecutor";

    @Value("${jelenoid.executor.core-pool-size}")
    private int corePoolSize;

    @Value("${jelenoid.executor.max-pool-size}")
    private int maxPoolSize;

    @Value("${jelenoid.executor.queue-capacity}")
    private int queueCapacity;

    @Bean(name = SESSION_TASK_EXECUTOR)
    public Executor sessionTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("session-creator-");
        executor.initialize();
        return executor;
    }
}
