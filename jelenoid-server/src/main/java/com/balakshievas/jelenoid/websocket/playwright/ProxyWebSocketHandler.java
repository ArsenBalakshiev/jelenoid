package com.balakshievas.jelenoid.websocket.playwright; // Замените на ваш пакет

import com.balakshievas.jelenoid.service.playwright.PlaywrightDockerService; // Замените на ваш сервис
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.*;

@Component
public class ProxyWebSocketHandler extends TextWebSocketHandler {

    private final Logger log = LoggerFactory.getLogger(ProxyWebSocketHandler.class);

    @Value("${jelenoid.playwright.max_sessions:10}")
    private int maxSessions;

    @Value("${jelenoid.playwright.queue_limit:100}")
    private int queueLimit;

    private Semaphore semaphore;
    private BlockingQueue<SessionPair> waitingQueue;
    private final ConcurrentHashMap<WebSocketSession, SessionPair> activeSessions = new ConcurrentHashMap<>();
    private final ExecutorService proxyExecutor = Executors.newCachedThreadPool();

    @Autowired
    private PlaywrightDockerService playwrightDockerService;

    @PostConstruct
    public void init() {
        this.semaphore = new Semaphore(maxSessions, true);
        this.waitingQueue = new LinkedBlockingQueue<>(queueLimit);
        log.info("ProxyWebSocketHandler initialized with max_sessions={} and queue_limit={}", maxSessions, queueLimit);
    }

    /**
     * Внутренний класс для хранения состояния каждой прокси-сессии.
     */
    static class SessionPair {
        final WebSocketSession clientSession;
        volatile WebSocketClient containerClient;
        final Object lock = new Object();
        volatile boolean connectionToContainerEstablished = false;
        final Queue<WebSocketMessage<?>> pendingMessages = new ConcurrentLinkedQueue<>();

        SessionPair(WebSocketSession clientSession) {
            this.clientSession = clientSession;
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("Session {}: Connection received.", session.getId());

        if (semaphore.tryAcquire()) {
            log.info("Session {}: Slot acquired immediately. Starting proxy.", session.getId());
            startProxyForSession(session);
        } else {
            log.warn("Session {}: No available slots. Attempting to queue.", session.getId());
            SessionPair pair = new SessionPair(session);
            boolean enqueued = waitingQueue.offer(pair);
            if (!enqueued) {
                log.error("Session {}: Proxy queue is full. Rejecting connection.", session.getId());
                closeSessionSilently(session, CloseStatus.SERVICE_OVERLOAD.withReason("Proxy queue is full."));
            } else {
                log.info("Session {}: Successfully queued. Waiting for a free slot.", session.getId());
            }
        }
    }

    private void startProxyForSession(WebSocketSession session) {
        SessionPair pair = new SessionPair(session);
        activeSessions.put(session, pair);

        proxyExecutor.submit(() -> {
            try {
                WebSocketClient containerClient = createContainerClient(pair);
                pair.containerClient = containerClient;

                if (!containerClient.connectBlocking(15, TimeUnit.SECONDS)) {
                    log.error("Session {}: Could not connect to container within timeout.", session.getId());
                    closeSessionSilently(session, CloseStatus.SERVER_ERROR);
                }
            } catch (Exception e) {
                log.error("Session {}: Failed to start proxy task.", session.getId(), e);
                closeSessionSilently(session, CloseStatus.SERVER_ERROR);
            }
        });
    }

    private WebSocketClient createContainerClient(SessionPair pair) throws URISyntaxException {
        String containerAddress = playwrightDockerService.getPlaywrightAddress();
        URI containerUri = URI.create(containerAddress);

        Map<String, String> headersToForward = new HashMap<>();
        pair.clientSession.getHandshakeHeaders().forEach((key, values) -> {
            if (!key.equalsIgnoreCase("Upgrade") && !key.equalsIgnoreCase("Connection") && !key.toLowerCase().startsWith("sec-websocket-")) {
                if (!values.isEmpty()) {
                    headersToForward.put(key, String.join(", ", values));
                }
            }
        });

        return new WebSocketClient(containerUri, headersToForward) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                log.info("Session {}: Connection to container {} established.", pair.clientSession.getId(), containerUri);
                synchronized (pair.lock) {
                    pair.connectionToContainerEstablished = true;
                    log.debug("Session {}: Flushing {} pending messages.", pair.clientSession.getId(), pair.pendingMessages.size());
                    while (!pair.pendingMessages.isEmpty()) {
                        WebSocketMessage<?> message = pair.pendingMessages.poll();
                        if (message instanceof TextMessage) this.send(((TextMessage) message).getPayload());
                        else if (message instanceof BinaryMessage) this.send(((BinaryMessage) message).getPayload());
                    }
                }
            }
            @Override
            public void onMessage(String message) { sendToClient(pair, new TextMessage(message)); }
            @Override

            public void onMessage(ByteBuffer bytes) { sendToClient(pair, new BinaryMessage(bytes)); }
            @Override

            public void onClose(int code, String reason, boolean remote) {
                log.warn("Session {}: Container connection closed. Code: {}, Reason: {}", pair.clientSession.getId(), code, reason);
                closeSessionSilently(pair.clientSession, new CloseStatus(code, reason));
            }
            @Override

            public void onError(Exception ex) {
                log.error("Session {}: Error in container connection.", pair.clientSession.getId(), ex);
                closeSessionSilently(pair.clientSession, CloseStatus.SERVER_ERROR);
            }
        };
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("Session {}: Client connection closed with status {}.", session.getId(), status.getCode());

        if (activeSessions.remove(session) != null) {
            SessionPair nextPair = waitingQueue.poll();
            if (nextPair != null) {
                log.info("Session {}: Slot is being passed to queued session {}.", session.getId(), nextPair.clientSession.getId());
                startProxyForSession(nextPair.clientSession);
            } else {
                semaphore.release();
                log.info("Slot released. Queue is empty. Available slots: {}.", semaphore.availablePermits());
            }
        } else {
            boolean removed = waitingQueue.removeIf(p -> p.clientSession.equals(session));
            if (removed) {
                log.info("Session {}: Removed from waiting queue before it could start.", session.getId());
            }
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

    private void processWebSocketMessage(WebSocketSession session, WebSocketMessage<?> message) {
        SessionPair pair = activeSessions.get(session);
        if (pair == null) return;

        synchronized (pair.lock) {
            if (pair.connectionToContainerEstablished) {
                if (message instanceof TextMessage) pair.containerClient.send(((TextMessage) message).getPayload());
                else if (message instanceof BinaryMessage) pair.containerClient.send(((BinaryMessage) message).getPayload());
            } else {
                pair.pendingMessages.add(message);
            }
        }
    }

    private void sendToClient(SessionPair pair, WebSocketMessage<?> message) {
        try {
            if (pair.clientSession.isOpen()) {
                synchronized (pair.clientSession) { // Доп. защита для отправки клиенту
                    pair.clientSession.sendMessage(message);
                }
            }
        } catch (IOException e) {
            log.error("Session {}: Failed to send message to client.", pair.clientSession.getId(), e);
            closeSessionSilently(pair.clientSession, CloseStatus.SERVER_ERROR);
        }
    }

    private void closeSessionSilently(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException ignored) {}
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down ProxyWebSocketHandler...");
        proxyExecutor.shutdownNow();
        activeSessions.values().forEach(pair -> {
            closeSessionSilently(pair.clientSession, CloseStatus.GOING_AWAY);
            if (pair.containerClient != null) {
                pair.containerClient.close();
            }
        });
        waitingQueue.clear();
        log.info("ProxyWebSocketHandler has been shut down.");
    }
}
