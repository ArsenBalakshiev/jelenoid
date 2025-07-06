package com.balakshievas.jelenoid.service;

import com.balakshievas.jelenoid.config.TaskExecutorConfig;
import com.balakshievas.jelenoid.dto.PendingRequest;
import com.balakshievas.jelenoid.dto.Session;
import com.balakshievas.jelenoid.dto.SessionPair;
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
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

@Service
public class ActiveSessionsService {

    private static final Logger log = LoggerFactory.getLogger(ActiveSessionsService.class);

    @Value("${jelenoid.limit}")
    private int seleniumSessionLimit;
    @Value("${jelenoid.queue.limit}")
    private int seleniumQueueLimit;
    @Value("${jelenoid.timeouts.session}")
    private long sessionTimeoutMillis;
    @Value("${jelenoid.timeouts.queue}")
    private long queueTimeoutMillis;

    private final Map<String, Session> seleniumActiveSessions = new ConcurrentHashMap<>();
    private BlockingQueue<PendingRequest> seleniumPendingRequests;
    private final AtomicInteger seleniumSessionsInProgress = new AtomicInteger(0);


    @Value("${jelenoid.playwright.max_sessions}")
    private int playwrightMaxSessions;

    @Value("${jelenoid.playwright.queue_limit}")
    private int playwrightQueueLimit;

    private BlockingQueue<SessionPair> playwrightWaitingQueue;
    private final ConcurrentHashMap<WebSocketSession, SessionPair> playwrightActiveSessions = new ConcurrentHashMap<>();

    private Semaphore playwrightSemaphore;

    @Autowired
    private ContainerManagerService containerManagerService;

    @Lazy
    @Autowired
    private SessionService sessionService;

    @PostConstruct
    public void init() {
        this.seleniumPendingRequests = new LinkedBlockingQueue<>(seleniumQueueLimit);
        this.playwrightWaitingQueue = new LinkedBlockingQueue<>(playwrightQueueLimit);
        this.playwrightSemaphore = new Semaphore(playwrightMaxSessions, true);
    }

    public synchronized boolean tryReserveSlot() {
        if (seleniumSessionsInProgress.get() < seleniumSessionLimit) {
            seleniumSessionsInProgress.incrementAndGet();
            log.info("Slot reserved. Total in-progress: {}/{}", seleniumSessionsInProgress.get(), seleniumSessionLimit);
            return true;
        }
        return false;
    }

    public void releaseSlot() {
        int current = seleniumSessionsInProgress.decrementAndGet();
        log.info("Slot released. Total in-progress: {}/{}", current, seleniumSessionLimit);
    }

    public void sessionSuccessfullyCreated(String hubSessionId, Session session) {
        seleniumActiveSessions.put(hubSessionId, session);
        log.info("Session {} is now active. Active (real): {}, Total in-progress: {}",
                hubSessionId, seleniumActiveSessions.size(), seleniumSessionsInProgress.get());
    }

    public Session sessionDeleted(String hubSessionId) {
        Session session = seleniumActiveSessions.remove(hubSessionId);
        if (session != null) {
            releaseSlot();
        }
        return session;
    }

    public Session get(String sessionId) { return seleniumActiveSessions.get(sessionId); }
    public boolean offerToQueue(PendingRequest pendingRequest) { return seleniumPendingRequests.offer(pendingRequest); }
    public PendingRequest pollFromQueue() { return seleniumPendingRequests.poll(); }
    public int getQueueSize() { return seleniumPendingRequests.size(); }
    public int getInProgressCount() { return seleniumSessionsInProgress.get(); }


    @Scheduled(fixedRateString = "${jelenoid.timeouts.startup}")
    @Async(TaskExecutorConfig.SESSION_TASK_EXECUTOR)
    public void checkInactiveSessions() {
        seleniumActiveSessions.entrySet().removeIf(entry -> {
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

        seleniumPendingRequests.removeIf(entry -> {
            if (System.currentTimeMillis() - entry.getStartTime() > queueTimeoutMillis) {
                entry.getFuture().complete(Map.of("value", Map.of("error", "session not created",
                        "message", "Queue timeout")));
                entry.getFuture().cancel(true);
                log.warn("Pending request has timed out. Releasing slot.");
                return true;
            }
            return false;
        });
    }

    @PreDestroy
    public void cleanup() {
        log.info("Shutting down... stopping all {} active containers.", seleniumActiveSessions.size());
        seleniumActiveSessions.values().parallelStream().forEach(session -> {
            containerManagerService.stopContainer(session.getContainerInfo().getContainerId());
        });
        seleniumActiveSessions.clear();
        seleniumPendingRequests.clear();
        seleniumSessionsInProgress.set(0);
    }

    public Map<String, Session> getSeleniumActiveSessions() {
        return seleniumActiveSessions;
    }

    public BlockingQueue<PendingRequest> getSeleniumPendingRequests() {
        return seleniumPendingRequests;
    }

    public Integer getActiveSessionsCount() {
        return seleniumActiveSessions.size();
    }

    public Integer getPendingRequestsCount() {
        return seleniumPendingRequests.size();
    }

    public boolean offerPlaywrightQueue(SessionPair sessionPair) {
        return playwrightWaitingQueue.offer(sessionPair);
    }

    public SessionPair pollFromPlaywrightQueue() {
        return playwrightWaitingQueue.poll();
    }

    public boolean removeFromPlaywrightQueueIf(Predicate<SessionPair> filter) {
        return playwrightWaitingQueue.removeIf(filter);
    }

    public void clearPlaywrightWaitingQueue() {
        playwrightWaitingQueue.clear();
    }

    public SessionPair putPlaywrightActiveSession(WebSocketSession session, SessionPair sessionPair) {
        return playwrightActiveSessions.put(session, sessionPair);
    }

    public SessionPair removePlaywrightActiveSession(WebSocketSession session) {
        return playwrightActiveSessions.remove(session);
    }

    public SessionPair getPlaywrightActiveSession(WebSocketSession session) {
        return playwrightActiveSessions.get(session);
    }

    public int getPlaywrightMaxSessions() {
        return playwrightMaxSessions;
    }

    public int getPlaywrightQueueLimit() {
        return playwrightQueueLimit;
    }

    public BlockingQueue<SessionPair> getPlaywrightWaitingQueue() {
        return playwrightWaitingQueue;
    }

    public ConcurrentHashMap<WebSocketSession, SessionPair> getPlaywrightActiveSessions() {
        return playwrightActiveSessions;
    }

    public int getSeleniumSessionLimit() {
        return seleniumSessionLimit;
    }

    public boolean tryAcquirePlaywrightSlot() {
        return playwrightSemaphore.tryAcquire();
    }

    public void releasePlaywrightSlot() {
        playwrightSemaphore.release();
    }

    public int availablePlaywrightSlots() {
        return playwrightSemaphore.availablePermits();
    }

    public int usedPlaywrightSlots() {
        return playwrightMaxSessions - availablePlaywrightSlots();
    }
}