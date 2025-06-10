package com.balakshievas.jelenoid.service;

import com.balakshievas.jelenoid.dto.PendingRequest;
import com.balakshievas.jelenoid.dto.Session;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ActiveSessionsService {

    private static final Logger log = LoggerFactory.getLogger(ActiveSessionsService.class);
    private final Map<String, Session> activeSessions = new ConcurrentHashMap<>();

    private BlockingQueue<PendingRequest> pendingRequests;

    @Value("${jelenoid.queue.limit}")
    private int queueLimit;

    @Autowired
    private ContainerManagerService containerManagerService; // Нужен для остановки контейнеров

    @Autowired
    @Lazy
    private SessionService sessionService;

    @Value("${jelenoid.timeouts.session:30000}")
    private long sessionTimeout;

    @PostConstruct
    public void init() {
        this.pendingRequests = new LinkedBlockingQueue<>(queueLimit);
    }

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

    public int getActiveSessionsCount() {
        return activeSessions.size();
    }

    public BlockingQueue<PendingRequest> getPendingRequests() {
        return pendingRequests;
    }

    public int getPendingRequestsCount() {
        return pendingRequests.size();
    }

    public boolean offerToQueue(PendingRequest pendingRequest) {
        return pendingRequests.offer(pendingRequest);
    }

    public PendingRequest pollFromQueue() {
        return pendingRequests.poll();
    }

    @Scheduled(fixedRateString = "${jelenoid.timeouts.startup}")
    @Async
    public void checkInactiveSessions() {
        // Используем AtomicBoolean для хранения флага, так как мы внутри лямбды
        final AtomicBoolean sessionWasRemoved = new AtomicBoolean(false);

        activeSessions.entrySet().removeIf(entry -> {
            Session session = entry.getValue();
            if (System.currentTimeMillis() - session.getLastActivity() > sessionTimeout) {
                log.warn("Session {} has timed out. Stopping container {}.", session.hubSessionId(), session.containerInfo().getContainerId());
                containerManagerService.stopContainer(session.containerInfo().getContainerId());

                // Устанавливаем флаг, что мы удалили сессию
                sessionWasRemoved.set(true);
                return true;
            }
            return false;
        });

        if (sessionWasRemoved.get()) {
            log.info("Orphaned session(s) removed by cleanup cycle. Triggering queue processing.");
            sessionService.processQueue();
        }
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
