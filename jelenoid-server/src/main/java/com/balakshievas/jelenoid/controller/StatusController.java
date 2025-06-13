package com.balakshievas.jelenoid.controller;

import com.balakshievas.jelenoid.dto.PendingRequest;
import com.balakshievas.jelenoid.dto.QueuedRequestInfo;
import com.balakshievas.jelenoid.dto.Session;
import com.balakshievas.jelenoid.dto.StatusResponse;
import com.balakshievas.jelenoid.service.ActiveSessionsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/status")
public class StatusController {

    @Autowired
    private ActiveSessionsService activeSessionsService;

    @Value("${jelenoid.limit}")
    private int sessionLimit;

    @GetMapping("")
    public StatusResponse getStatus() {

        List<Session> sessions = activeSessionsService.getActiveSessions()
                .values().stream().toList();

        List<QueuedRequestInfo> queuedRequestInfos = activeSessionsService.getPendingRequests().stream()
                .map(this::toQueuedRequestInfo)
                .toList();

        return new StatusResponse(
                sessionLimit,
                sessions.size(),
                activeSessionsService.getInProgressCount(),
                activeSessionsService.getQueueSize(),
                sessions,
                queuedRequestInfos
        );

    }

    private QueuedRequestInfo toQueuedRequestInfo(PendingRequest pendingRequest) {

        String browserName = null;
        String browserVersion = null;

        Map<String, Object> capabilitiesRequest = (Map<String, Object>) pendingRequest.getRequestBody().get("capabilities");
        Map<String, Object> alwaysMatch = (Map<String, Object>) capabilitiesRequest.getOrDefault("alwaysMatch", Collections.emptyMap());
        List<Map<String, Object>> firstMatch = (List<Map<String, Object>>) capabilitiesRequest.getOrDefault("firstMatch", List.of(Collections.emptyMap()));

        for (Map<String, Object> firstMatchOption : firstMatch) {
            Map<String, Object> mergedCapabilities = new HashMap<>(alwaysMatch);
            mergedCapabilities.putAll(firstMatchOption);
            browserName = (String) mergedCapabilities.getOrDefault("browserName", "unknown");
            browserVersion = (String) mergedCapabilities.getOrDefault("browserVersion", "unknown");
        }

        return new QueuedRequestInfo(browserName, browserVersion, pendingRequest.getQueuedTime());
    }
}
