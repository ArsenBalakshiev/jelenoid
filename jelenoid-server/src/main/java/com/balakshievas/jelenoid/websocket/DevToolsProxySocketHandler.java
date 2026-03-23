package com.balakshievas.jelenoid.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class DevToolsProxySocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DevToolsProxySocketHandler.class);
    private final URI targetUri;
    private volatile WebSocketSession targetSession;
    private final CountDownLatch connectionLatch = new CountDownLatch(1);

    public DevToolsProxySocketHandler(URI targetUri) {
        this.targetUri = targetUri;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession clientSession) throws Exception {
        log.info("CDP Proxy: Client-side connection established: {}. Attempting to connect to target: {}", clientSession.getId(), targetUri);

        WebSocketClient client = new StandardWebSocketClient();

        TextWebSocketHandler forwardToClientHandler = new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
                if (clientSession.isOpen()) {
                    log.debug("CDP Proxy: Forwarding message from container to client: {}", message.getPayload());
                    clientSession.sendMessage(message);
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                log.info("CDP Proxy: Target connection closed. Closing client session: {}", clientSession.getId());
                if (clientSession.isOpen()) {
                    clientSession.close(status);
                }
            }
        };

        try {
            this.targetSession = client.execute(forwardToClientHandler, targetUri.toString()).get();
            log.info("CDP Proxy: Successfully connected to target WebSocket: {}", targetUri);
        } catch (InterruptedException | ExecutionException e) {
            log.error("CDP Proxy: Failed to connect to target WebSocket", e);
            clientSession.close(CloseStatus.SERVER_ERROR);
        } finally {
            connectionLatch.countDown();
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession clientSession, TextMessage message) throws IOException {
        try {
            connectionLatch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("CDP Proxy: Interrupted while waiting for connection to establish");
        }

        if (targetSession != null && targetSession.isOpen()) {
            log.debug("CDP Proxy: Forwarding message from client to container: {}", message.getPayload());
            targetSession.sendMessage(message);
        } else {
            log.warn("CDP Proxy: Dropping message because target session is not open: {}", message.getPayload());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession clientSession, CloseStatus status) throws Exception {
        if (targetSession != null && targetSession.isOpen()) {
            targetSession.close(status);
        }
        log.info("CDP Proxy: Client-side connection closed: {} with status {}", clientSession.getId(), status);
    }
}