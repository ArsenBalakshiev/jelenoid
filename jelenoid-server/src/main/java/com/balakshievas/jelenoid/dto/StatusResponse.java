package com.balakshievas.jelenoid.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.net.URI;
import java.util.List;

@Data
@AllArgsConstructor
public class StatusResponse {
    private final SeleniumStat seleniumStat;

    private final PlaywrightStat playwrightStat;

    @Data
    @AllArgsConstructor
    public static class SeleniumStat {
        private final int total;
        private final int used;
        private final int queued;
        private final int inProgress;
        private final List<Session> sessions;
        private final List<QueuedRequestInfo> queuedRequests;
    }

    @Data
    @AllArgsConstructor
    public static class PlaywrightStat {
        private final int maxPlaywrightSessionsSize;
        private final int activePlaywrightSessionsSize;
        private final int queuedPlaywrightSessionsSize;
        private final List<SessionPairInfo> activePlaywrightSessions;
        private final List<SessionPairInfo> queuedPlaywrightSessions;

        @Data
        @AllArgsConstructor
        @NoArgsConstructor
        public static class SessionPairInfo {
            private String clientSessionId;
            private URI clientSessionUrl;
            private URI containerClientUrl;
            private ContainerInfo containerInfo;
        }

    }
}
