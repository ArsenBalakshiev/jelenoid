package com.balakshievas.jelenoid.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class Session {

    private ContainerInfo containerInfo;
    private String version;

    public long getLastActivity() {
        return containerInfo.getLastActivity();
    }

    public void updateActivity() {
        containerInfo.updateActivity();
    }

    public Instant getStartTime() {
        return containerInfo.getStartTime();
    }

}
