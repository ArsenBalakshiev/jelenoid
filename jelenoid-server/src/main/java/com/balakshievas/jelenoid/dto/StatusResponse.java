package com.balakshievas.jelenoid.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class StatusResponse {
    private final int total;
    private final int used;
    private final int queued;
    private final int inProgress;
    private final List<Session> sessions;
    private final List<QueuedRequestInfo> queuedRequests;
}
