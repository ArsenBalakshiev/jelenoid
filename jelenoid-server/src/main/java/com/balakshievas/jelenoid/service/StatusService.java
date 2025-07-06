package com.balakshievas.jelenoid.service;

import com.balakshievas.jelenoid.dto.*;
import com.balakshievas.jelenoid.dto.StatusResponse.PlaywrightStat.SessionPairInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class StatusService {

    private final ActiveSessionsService activeSessionsService;

    @Autowired
    public StatusService(ActiveSessionsService activeSessionsService) {
        this.activeSessionsService = activeSessionsService;
    }

    public StatusResponse buildStatus() {
        List<Session> sessions = activeSessionsService.getSeleniumActiveSessions()
                .values().stream().toList();

        List<QueuedRequestInfo> queuedRequestInfos = activeSessionsService.getSeleniumPendingRequests().stream()
                .map(this::toQueuedRequestInfo)
                .toList();

        StatusResponse.SeleniumStat seleniumStat = new StatusResponse.SeleniumStat(
                activeSessionsService.getSeleniumSessionLimit(),
                sessions.size(),
                activeSessionsService.getQueueSize(),
                activeSessionsService.getInProgressCount(),
                sessions,
                queuedRequestInfos
        );

        List<SessionPair> activePlaywrightSessions = activeSessionsService.getPlaywrightActiveSessions()
                .values()
                .stream().toList();

        List<SessionPair> queuePlaywrightSessions = activeSessionsService.getPlaywrightWaitingQueue()
                .stream()
                .toList();

        StatusResponse.PlaywrightStat playwrightStat = new StatusResponse.PlaywrightStat(
                activeSessionsService.getPlaywrightMaxSessions(),
                activeSessionsService.usedPlaywrightSlots(),
                queuePlaywrightSessions.size(),
                convertToSessionPairInfo(activePlaywrightSessions),
                convertToSessionPairInfo(queuePlaywrightSessions)
        );

        return new StatusResponse(seleniumStat, playwrightStat);
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

    private List<SessionPairInfo> convertToSessionPairInfo(List<SessionPair> sessions) {

        return sessions.stream()
                .map(e -> {

                    SessionPairInfo sessionPairInfo = new SessionPairInfo();

                    if (e.getClientSession() != null) {
                        sessionPairInfo.setClientSessionId(e.getClientSession().getId());
                        sessionPairInfo.setClientSessionUrl(e.getClientSession().getUri());
                    }

                    if (e.getContainerClient() != null) {
                        sessionPairInfo.setContainerClientUrl(e.getContainerClient().getURI());
                    }

                    if (e.getContainer() != null) {
                        sessionPairInfo.setContainerInfo(e.getContainer());
                    }

                    return sessionPairInfo;

                })
                .toList();

    }

}
