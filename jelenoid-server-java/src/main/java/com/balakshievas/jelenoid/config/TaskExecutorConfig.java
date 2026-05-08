package com.balakshievas.jelenoid.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;

import java.util.concurrent.Executors;

@Configuration
public class TaskExecutorConfig {
    public static final String SESSION_TASK_EXECUTOR = "sessionTaskExecutor";

    @Bean(name = SESSION_TASK_EXECUTOR)
    public AsyncTaskExecutor sessionTaskExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }
}
