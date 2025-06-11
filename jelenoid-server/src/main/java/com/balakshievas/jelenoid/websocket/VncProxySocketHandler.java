package com.balakshievas.jelenoid.websocket;

import com.balakshievas.jelenoid.dto.ContainerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class VncProxySocketHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(VncProxySocketHandler.class);
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final ContainerInfo container;
    private Socket vncSocket;

    // Конструктор теперь принимает готовую информацию о контейнере
    public VncProxySocketHandler(ContainerInfo container) {
        this.container = container;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession clientSession) throws Exception {
        if (container == null) {
            log.error("VNC Proxy: Container info was not provided to the handler.");
            clientSession.close(CloseStatus.SERVER_ERROR.withReason("Container info missing"));
            return;
        }

        try {
            this.vncSocket = new Socket(container.getContainerName(), 5900);
            log.info("VNC Proxy: TCP socket connected to {}:5900 for client {}", container.getContainerName(), clientSession.getId());
        } catch (IOException e) {
            log.error("VNC Proxy: Failed to connect to VNC socket in container {}", container.getContainerName(), e);
            clientSession.close(CloseStatus.SERVER_ERROR.withReason("Could not connect to VNC in container"));
            return;
        }

        executor.submit(() -> {
            try (InputStream vncInput = vncSocket.getInputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while (!executor.isShutdown() && (bytesRead = vncInput.read(buffer)) != -1) {
                    if (clientSession.isOpen()) {
                        clientSession.sendMessage(new BinaryMessage(buffer, 0, bytesRead, true));
                    } else {
                        break;
                    }
                }
            } catch (IOException e) {
                // Это нормальное завершение, когда клиент закрывает соединение
            } finally {
                closeConnections(clientSession);
            }
        });
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws IOException {
        if (vncSocket != null && vncSocket.isConnected()) {
            vncSocket.getOutputStream().write(message.getPayload().array(), 0, message.getPayloadLength());
            vncSocket.getOutputStream().flush();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        closeConnections(session);
    }

    private void closeConnections(WebSocketSession session) {
        if (!executor.isShutdown()) {
            log.info("VNC Proxy: Closing all connections for client session {}", session.getId());
            executor.shutdownNow();
            try {
                if (vncSocket != null) vncSocket.close();
            } catch (IOException e) { /* ignore */ }
            try {
                if (session.isOpen()) session.close();
            } catch (IOException e) { /* ignore */ }
        }
    }
}