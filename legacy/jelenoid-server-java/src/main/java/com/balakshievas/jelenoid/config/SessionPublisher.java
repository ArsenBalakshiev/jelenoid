package com.balakshievas.jelenoid.config;

import com.balakshievas.jelenoid.dto.SessionInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.nats.client.Connection;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
public class SessionPublisher {

    private static final String SUBJECT = "sessions.events";
    private final Connection nats;

    public SessionPublisher(Connection nats) {
        this.nats = nats;
    }

    public void publish(SessionInfo sessionInfo) {
        if (nats == null) {
            return;
        }
        try {
            byte[] json = new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .writeValueAsBytes(sessionInfo);
            nats.publish(SUBJECT, json);
        } catch (Exception ignored) {
        }
    }

    public SessionInfo createSessionAndPublish(String platform, String version) {

        SessionInfo sessionInfo = new SessionInfo(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                null,
                platform,
                version,
                "started",
                null
        );
        publish(sessionInfo);
        return sessionInfo;
    }

    public SessionInfo endSessionByRemoteAndPublish(SessionInfo sessionInfo) {
        if(sessionInfo == null) {
            return null;
        }
        sessionInfo.setEndTime(LocalDateTime.now());
        sessionInfo.setStatus("finished");
        sessionInfo.setEndedBy("by remote");
        publish(sessionInfo);
        return sessionInfo;
    }

    public SessionInfo errorSessionAndPublish(SessionInfo sessionInfo) {
        if(sessionInfo == null) {
            return null;
        }
        sessionInfo.setEndTime(LocalDateTime.now());
        sessionInfo.setStatus("error");
        sessionInfo.setEndedBy("error");
        publish(sessionInfo);
        return sessionInfo;
    }

    public SessionInfo endInactiveSessionAndPublish(SessionInfo sessionInfo) {
        if(sessionInfo == null) {
            return null;
        }
        sessionInfo.setEndTime(LocalDateTime.now());
        sessionInfo.setStatus("finished");
        sessionInfo.setEndedBy("by inactive");
        publish(sessionInfo);
        return sessionInfo;
    }

    public SessionInfo cleanupSessionAndPublish(SessionInfo sessionInfo) {
        if(sessionInfo == null) {
            return null;
        }
        sessionInfo.setEndTime(LocalDateTime.now());
        sessionInfo.setStatus("finished");
        sessionInfo.setEndedBy("cleanup");
        publish(sessionInfo);
        return sessionInfo;
    }
}
