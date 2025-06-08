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
import java.util.concurrent.ExecutionException;

public class DevToolsProxySocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DevToolsProxySocketHandler.class);
    private final URI targetUri;
    private WebSocketSession targetSession;

    public DevToolsProxySocketHandler(URI targetUri) {
        this.targetUri = targetUri;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession clientSession) throws Exception {
        log.info("CDP Proxy: Client-side connection established: {}. Attempting to connect to target: {}", clientSession.getId(), targetUri);

        WebSocketClient client = new StandardWebSocketClient();

        // Этот обработчик будет пересылать текстовые сообщения ОТ контейнера К клиенту
        TextWebSocketHandler forwardToClientHandler = new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
                if (clientSession.isOpen()) {
                    log.debug("CDP Proxy: Forwarding message from container to client: {}", message.getPayload());
                    clientSession.sendMessage(message);
                }
            }
        };

        try {
            this.targetSession = client.execute(forwardToClientHandler, targetUri.toString()).get();
            log.info("CDP Proxy: Successfully connected to target WebSocket: {}", targetUri);
        } catch (InterruptedException | ExecutionException e) {
            log.error("CDP Proxy: Failed to connect to target WebSocket", e);
            clientSession.close(CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession clientSession, TextMessage message) throws IOException {
        // Пересылаем текстовые сообщения ОТ клиента В контейнер
        if (targetSession != null && targetSession.isOpen()) {
            log.debug("CDP Proxy: Forwarding message from client to container: {}", message.getPayload());
            targetSession.sendMessage(message);
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