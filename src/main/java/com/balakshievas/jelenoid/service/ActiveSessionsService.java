package com.balakshievas.jelenoid.service;

import com.balakshievas.jelenoid.dto.Session;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ActiveSessionsService {

    private static final Logger log = LoggerFactory.getLogger(ActiveSessionsService.class);
    private final Map<String, Session> activeSessions = new ConcurrentHashMap<>();

    @Autowired
    private ContainerManagerService containerManagerService; // Нужен для остановки контейнеров

    @Value("${jelenoid.timeouts.session:30000}")
    private long sessionTimeout;

    public void putSession(String sessionId, Session session) {
        activeSessions.put(sessionId, session);
    }

    public Session getSession(String sessionId) {
        return activeSessions.get(sessionId);
    }

    public Session removeSession(String sessionId) {
        return activeSessions.remove(sessionId);
    }

    public Map<String, Session> getActiveSessions() {
        return activeSessions;
    }

    @Scheduled(fixedRateString = "${jelenoid.timeouts.startup}")
    @Async
    public void checkInactiveSessions() {
        activeSessions.entrySet().removeIf(entry -> {
            Session session = entry.getValue();
            if (System.currentTimeMillis() - session.getLastActivity() > sessionTimeout) {
                log.info("Session {} timed out. Stopping container...", session.hubSessionId());
                containerManagerService.stopContainer(session.containerInfo().getContainerId());
                return true;
            }
            return false;
        });
    }

    @PreDestroy
    public void cleanup() {
        activeSessions.values().parallelStream().forEach(elem -> {
            try {
                containerManagerService.stopContainer(elem.containerInfo().getContainerId());
            } catch (Exception e) {
                log.error("Error stopping container {}", elem.containerInfo().getContainerId(), e);
            }
        });
    }
}
