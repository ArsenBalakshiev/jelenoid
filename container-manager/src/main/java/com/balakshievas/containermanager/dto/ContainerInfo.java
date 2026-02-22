package com.balakshievas.containermanager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

@Data
@AllArgsConstructor
public class ContainerInfo {
    private final String containerId;
    private final String containerName;
    private volatile long lastActivity;
    private final Instant startTime;

    public ContainerInfo(String containerId, String containerName) {
        this.containerId = containerId;
        this.containerName = containerName;
        startTime = Instant.now();
        updateActivity();
    }

    public void updateActivity() {
        this.lastActivity = System.currentTimeMillis();
    }
}
