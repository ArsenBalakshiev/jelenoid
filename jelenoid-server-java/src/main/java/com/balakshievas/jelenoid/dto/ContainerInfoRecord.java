package com.balakshievas.jelenoid.dto;

import java.time.Instant;

public record ContainerInfoRecord(
        String containerId,
        String containerName,
        long lastActivity,
        Instant startTime
) {

}
