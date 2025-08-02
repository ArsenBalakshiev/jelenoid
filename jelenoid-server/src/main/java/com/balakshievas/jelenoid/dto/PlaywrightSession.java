package com.balakshievas.jelenoid.dto;

import lombok.Data;
import org.java_websocket.client.WebSocketClient;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@Data
public class PlaywrightSession extends Session {

    private final WebSocketSession clientSession;
    private volatile WebSocketClient containerClient;
    private final Object lock = new Object();
    private volatile boolean connectionToContainerEstablished = false;
    private final Queue<WebSocketMessage<?>> pendingMessages = new ConcurrentLinkedQueue<>();

    public PlaywrightSession(WebSocketSession clientSession) {
        this.clientSession = clientSession;
    }
}
