package com.balakshievas.jelenoid.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Data
@AllArgsConstructor
public class PendingRequest {
    private final Map<String, Object> requestBody;
    private CompletableFuture<Map<String, Object>> future;
    private final Instant queuedTime;
    private final long startTime;
}
