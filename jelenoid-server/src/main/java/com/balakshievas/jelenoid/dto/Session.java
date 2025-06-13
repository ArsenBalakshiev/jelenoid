package com.balakshievas.jelenoid.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

@Data
@AllArgsConstructor
public class Session {
    private final String hubSessionId;
    private final String remoteSessionId;
    private final ContainerInfo containerInfo;
    private final String browserName;
    private final String browserVersion;
    private final boolean vncEnabled;

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
