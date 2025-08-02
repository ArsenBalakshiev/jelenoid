package com.balakshievas.jelenoid.websocket.playwright;

import com.balakshievas.jelenoid.config.TaskExecutorConfig;
import com.balakshievas.jelenoid.dto.PlaywrightSession;
import com.balakshievas.jelenoid.dto.StatusChangedEvent;
import com.balakshievas.jelenoid.service.ActiveSessionsService;
import com.balakshievas.jelenoid.service.playwright.PlaywrightDockerService;
import jakarta.annotation.PreDestroy;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class ProxyWebSocketHandler extends TextWebSocketHandler {

    private final Logger log = LoggerFactory.getLogger(ProxyWebSocketHandler.class);

    @Value("${jelenoid.playwright.port}")
    private Integer playwrightPort;

    @Value("${jelenoid.timeouts.session}")
    private long sessionTimeoutMillis;

    @Value("${jelenoid.playwright.default_version}")
    private String defaultPlaywrightVersion;

    private final ExecutorService proxyExecutor = Executors.newCachedThreadPool();

    @Autowired
    private ActiveSessionsService activeSessionsService;

    @Autowired
    private PlaywrightDockerService playwrightDockerService;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        PlaywrightSession pair = new PlaywrightSession(session);
        activeSessionsService.putPlaywrightActiveSession(session, pair);
        log.info("Session {}: Connection received.", session.getId());

        if (activeSessionsService.tryAcquirePlaywrightSlot()) {
            log.info("Session {}: Slot acquired immediately. Starting proxy.", session.getId());
            startProxyForSession(session, pair);
        } else {
            log.warn("Session {}: No available slots. Attempting to queue.", session.getId());
            boolean enqueued = activeSessionsService.offerPlaywrightQueue(pair);
            if (!enqueued) {
                log.error("Session {}: Proxy queue is full. Rejecting connection.", session.getId());
                closeSessionSilently(session, CloseStatus.SERVICE_OVERLOAD.withReason("Proxy queue is full."));
            } else {
                log.info("Session {}: Successfully queued. Waiting for a free slot.", session.getId());
            }
        }
        publisher.publishEvent(new StatusChangedEvent());
    }

    private void startProxyForSession(WebSocketSession session, PlaywrightSession pair) {
        proxyExecutor.submit(() -> {
            try {
                String playwrightContainerVersion = getPlaywrightVersion(session);

                if (playwrightContainerVersion != null) {
                    pair.setContainerInfo(playwrightDockerService.startPlaywrightContainer(playwrightContainerVersion));
                    pair.setVersion(playwrightContainerVersion);
                } else {
                    pair.setContainerInfo(playwrightDockerService.startPlaywrightContainer(defaultPlaywrightVersion));
                    pair.setVersion(defaultPlaywrightVersion);
                }
                if (pair.getContainerInfo() == null) {
                    throw new RuntimeException("Failed to start a new Playwright container.");
                }

                log.info("Session {}: Started new container {} at address {}",
                        session.getId(), pair.getContainerInfo().getContainerId(), pair.getContainerInfo().getContainerName());

                WebSocketClient containerClient = createContainerClient(pair);
                pair.setContainerClient(containerClient);

                if (!containerClient.connectBlocking(15, TimeUnit.SECONDS)) {
                    log.error("Session {}: Could not connect to container within timeout.", session.getId());
                    closeSessionSilently(session, CloseStatus.SERVER_ERROR);
                    publisher.publishEvent(new StatusChangedEvent());
                    return;
                }
                publisher.publishEvent(new StatusChangedEvent());
            } catch (Exception e) {
                log.error("Session {}: Failed to start proxy task.", session.getId(), e);
                activeSessionsService.removePlaywrightActiveSession(session);
                activeSessionsService.releasePlaywrightSlot();
                closeSessionSilently(session, CloseStatus.SERVER_ERROR);
                publisher.publishEvent(new StatusChangedEvent());
            }
        });
    }

    private WebSocketClient createContainerClient(PlaywrightSession pair) {
        URI containerUri = URI.create("ws://" + pair.getContainerInfo().getContainerName() + ":" + playwrightPort);

        Map<String, String> headersToForward = new HashMap<>();
        pair.getClientSession().getHandshakeHeaders().forEach((key, values) -> {
            if (!key.equalsIgnoreCase("Upgrade")
                    && !key.equalsIgnoreCase("Connection")
                    && !key.toLowerCase().startsWith("sec-websocket-")) {
                if (!values.isEmpty()) {
                    headersToForward.put(key, String.join(", ", values));
                }
            }
        });

        return new WebSocketClient(containerUri, headersToForward) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                log.info("Session {}: Connection to container {} established.",
                        pair.getClientSession().getId(), containerUri);
                synchronized (pair.getLock()) {
                    pair.setConnectionToContainerEstablished(true);
                    log.debug("Session {}: Flushing {} pending messages.", pair.getClientSession().getId(),
                            pair.getPendingMessages().size());
                    while (!pair.getPendingMessages().isEmpty()) {
                        WebSocketMessage<?> message = pair.getPendingMessages().poll();
                        if (message instanceof TextMessage) this.send(((TextMessage) message).getPayload());
                        else if (message instanceof BinaryMessage) this.send(((BinaryMessage) message).getPayload());
                    }
                }
            }

            @Override
            public void onMessage(String message) {
                sendToClient(pair, new TextMessage(message));
            }

            @Override
            public void onMessage(ByteBuffer bytes) {
                sendToClient(pair, new BinaryMessage(bytes));
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                log.warn("Session {}: Container connection closed. Code: {}, Reason: {}",
                        pair.getClientSession().getId(), code, reason);
                closeSessionSilently(pair.getClientSession(), new CloseStatus(code, reason));
                proxyExecutor.submit(() -> {
                    playwrightDockerService.stopContainer(pair.getContainerInfo().getContainerId());
                    pair.setContainerInfo(null);
                });
                publisher.publishEvent(new StatusChangedEvent());
            }

            @Override
            public void onError(Exception ex) {
                log.error("Session {}: Error in container connection.", pair.getClientSession().getId(), ex);
                closeSessionSilently(pair.getClientSession(), CloseStatus.SERVER_ERROR);
                publisher.publishEvent(new StatusChangedEvent());
            }
        };
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("Session {}: Client connection closed with status {}.", session.getId(), status.getCode());

        PlaywrightSession removedPair = activeSessionsService.removePlaywrightActiveSession(session);
        boolean stateChanged = false;

        if (removedPair != null) {
            stateChanged = true;
            if (removedPair.getContainerInfo() != null) {
                log.info("Session {}: Cleaning up dedicated container {}.", session.getId(),
                        removedPair.getContainerInfo().getContainerId());
                proxyExecutor.submit(() -> {
                    playwrightDockerService.stopContainer(removedPair.getContainerInfo().getContainerId());
                    removedPair.setContainerInfo(null);
                });
            }

            PlaywrightSession nextPair = activeSessionsService.pollFromPlaywrightQueue();
            if (nextPair != null) {
                log.info("Session {}: Slot is being passed to queued session {}.", session.getId(),
                        nextPair.getClientSession().getId());
                startProxyForSession(nextPair.getClientSession(), nextPair);
            } else {
                activeSessionsService.releasePlaywrightSlot();
                log.info("Slot released. Queue is empty. Available slots: {}.",
                        activeSessionsService.availablePlaywrightSlots());
            }
        } else {
            boolean removed = activeSessionsService
                    .removeFromPlaywrightQueueIf(p -> p.getClientSession().equals(session));
            if (removed) {
                log.info("Session {}: Removed from waiting queue before it could start.", session.getId());
                stateChanged = true;
            }
        }
        if (stateChanged) {
            publisher.publishEvent(new StatusChangedEvent());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        processWebSocketMessage(session, message);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        processWebSocketMessage(session, message);
    }

    private String getPlaywrightVersion(WebSocketSession session) {
        return (String) session.getAttributes()
                .get("playwrightVersion");
    }

    private void processWebSocketMessage(WebSocketSession session, WebSocketMessage<?> message) {
        PlaywrightSession pair = activeSessionsService.getPlaywrightActiveSession(session);
        if (pair == null) return;
        synchronized (pair.getLock()) {
            if (pair.getContainerInfo() != null) {
                pair.getContainerInfo().updateActivity();
            }
            if (pair.isConnectionToContainerEstablished()) {
                if (message instanceof TextMessage)
                    pair.getContainerClient().send(((TextMessage) message).getPayload());
                else if (message instanceof BinaryMessage)
                    pair.getContainerClient().send(((BinaryMessage) message).getPayload());
            } else {
                pair.getPendingMessages().add(message);
            }
        }
    }

    private void sendToClient(PlaywrightSession pair, WebSocketMessage<?> message) {
        try {
            if (pair.getClientSession().isOpen()) {
                synchronized (pair.getClientSession()) {
                    pair.getClientSession().sendMessage(message);
                }
            }
        } catch (IOException e) {
            log.error("Session {}: Failed to send message to client.", pair.getClientSession().getId(), e);
            closeSessionSilently(pair.getClientSession(), CloseStatus.SERVER_ERROR);
            publisher.publishEvent(new StatusChangedEvent());
        }
    }

    private void closeSessionSilently(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException ignored) {
        }
    }

    @Scheduled(fixedRateString = "${jelenoid.timeouts.startup}")
    @Async(TaskExecutorConfig.SESSION_TASK_EXECUTOR)
    public void checkSessionTimeouts() {
        long now = System.currentTimeMillis();
        activeSessionsService.getPlaywrightActiveSessions().forEach((session, pair) -> {
            if (pair.getContainerInfo() != null && (now - pair.getContainerInfo().getLastActivity()) > sessionTimeoutMillis) {
                log.warn("Session {} timed out due to inactivity. Closing connection.", session.getId());
                closeSessionSilently(session, CloseStatus.SESSION_NOT_RELIABLE);
                publisher.publishEvent(new StatusChangedEvent());
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down ProxyWebSocketHandler...");
        proxyExecutor.shutdownNow();
        activeSessionsService.getPlaywrightActiveSessions().values().forEach(pair -> {
            closeSessionSilently(pair.getClientSession(), CloseStatus.GOING_AWAY);
            if (pair.getContainerInfo() != null) {
                playwrightDockerService.stopContainer(pair.getContainerInfo().getContainerId());
                pair.setContainerInfo(null);
            }
        });
        activeSessionsService.clearPlaywrightWaitingQueue();
        publisher.publishEvent(new StatusChangedEvent());
        log.info("ProxyWebSocketHandler has been shut down.");
    }
}
