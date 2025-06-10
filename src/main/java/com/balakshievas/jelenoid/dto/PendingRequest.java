package com.balakshievas.jelenoid.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PendingRequest {
    private Map<String, Object> requestBody;
    private CompletableFuture<Map<String, Object>> future;
}
