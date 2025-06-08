package com.balakshievas.superselenoid.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ContainerInfo {
    private String containerId;
    private String containerName;
    private volatile long lastActivity;

    public ContainerInfo(String containerId, String containerName) {
        this.containerId = containerId;
        this.containerName = containerName;
        updateActivity();
    }

    public void updateActivity() {
        this.lastActivity = System.currentTimeMillis();
    }
}
