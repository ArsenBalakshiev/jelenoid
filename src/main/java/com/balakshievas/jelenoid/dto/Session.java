package com.balakshievas.jelenoid.dto;

public record Session(
        String hubSessionId,
        String remoteSessionId,
        ContainerInfo containerInfo
) {
    public long getLastActivity() {
        return containerInfo.getLastActivity();
    }

    public void updateActivity() {
        containerInfo.updateActivity();
    }
}
