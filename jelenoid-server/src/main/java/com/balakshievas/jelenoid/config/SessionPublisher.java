package com.balakshievas.jelenoid.config;

import com.balakshievas.jelenoid.dto.SessionInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.nats.client.Connection;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SessionPublisher {

    private static final String SUBJECT = "session";
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
                    .writeValueAsBytes(sessionInfo);
            nats.publish(SUBJECT, json);
        } catch (Exception ex) {
            log.warn("Failed to publish to NATS", ex);
        }
    }
}
