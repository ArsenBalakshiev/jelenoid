package com.balakshievas.jelenoid.service;

import com.balakshievas.jelenoid.config.TaskExecutorConfig;
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
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ActiveSessionsService {

    private static final Logger log = LoggerFactory.getLogger(ActiveSessionsService.class);

    @Value("${jelenoid.limit}")
    private int sessionLimit;
    @Value("${jelenoid.queue.limit}")
    private int queueLimit;
    @Value("${jelenoid.timeouts.session}")
    private long sessionTimeoutMillis;

    private final Map<String, Session> activeSessions = new ConcurrentHashMap<>();
    private BlockingQueue<PendingRequest> pendingRequests;
    private final AtomicInteger sessionsInProgress = new AtomicInteger(0);

    @Autowired
    private ContainerManagerService containerManagerService;
    @Autowired @Lazy
    private SessionService sessionService;

    @PostConstruct
    public void init() {
        this.pendingRequests = new LinkedBlockingQueue<>(queueLimit);
    }

    public synchronized boolean tryReserveSlot() {
        if (sessionsInProgress.get() < sessionLimit) {
            sessionsInProgress.incrementAndGet();
            log.info("Slot reserved. Total in-progress: {}/{}", sessionsInProgress.get(), sessionLimit);
            return true;
        }
        return false;
    }

    public void releaseSlot() {
        int current = sessionsInProgress.decrementAndGet();
        log.info("Slot released. Total in-progress: {}/{}", current, sessionLimit);
    }

    public void sessionSuccessfullyCreated(String hubSessionId, Session session) {
        activeSessions.put(hubSessionId, session);
        log.info("Session {} is now active. Active (real): {}, Total in-progress: {}",
                hubSessionId, activeSessions.size(), sessionsInProgress.get());
    }

    public Session sessionDeleted(String hubSessionId) {
        Session session = activeSessions.remove(hubSessionId);
        if (session != null) {
            releaseSlot();
        }
        return session;
    }

    public Session get(String sessionId) { return activeSessions.get(sessionId); }
    public boolean offerToQueue(PendingRequest pendingRequest) { return pendingRequests.offer(pendingRequest); }
    public PendingRequest pollFromQueue() { return pendingRequests.poll(); }
    public int getQueueSize() { return pendingRequests.size(); }
    public int getInProgressCount() { return sessionsInProgress.get(); }


    @Scheduled(fixedRateString = "${jelenoid.timeouts.startup}")
    @Async(TaskExecutorConfig.SESSION_TASK_EXECUTOR)
    public void checkInactiveSessions() {
        activeSessions.entrySet().removeIf(entry -> {
            Session session = entry.getValue();
            if (System.currentTimeMillis() - session.getLastActivity() > sessionTimeoutMillis) {
                log.warn("Session {} has timed out. Releasing slot and stopping container {}.",
                        session.getHubSessionId(), session.getContainerInfo().getContainerId());
                releaseSlot();
                containerManagerService.stopContainer(session.getContainerInfo().getContainerId());
                sessionService.processQueue();
                return true;
            }
            return false;
        });
    }

    @PreDestroy
    public void cleanup() {
        log.info("Shutting down... stopping all {} active containers.", activeSessions.size());
        activeSessions.values().parallelStream().forEach(session -> {
            containerManagerService.stopContainer(session.getContainerInfo().getContainerId());
        });
        activeSessions.clear();
        pendingRequests.clear();
        sessionsInProgress.set(0);
    }

    public Map<String, Session> getActiveSessions() {
        return activeSessions;
    }

    public BlockingQueue<PendingRequest> getPendingRequests() {
        return pendingRequests;
    }

    public Integer getActiveSessionsCount() {
        return activeSessions.size();
    }

    public Integer getPendingRequestsCount() {
        return pendingRequests.size();
    }
}