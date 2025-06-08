package com.balakshievas.jelenoid.websocket;

import com.balakshievas.jelenoid.config.SpringContext;
import com.balakshievas.jelenoid.dto.ContainerInfo;
import com.balakshievas.jelenoid.service.ActiveSessionsService;
import com.balakshievas.jelenoid.service.ContainerManagerService;
import com.balakshievas.jelenoid.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class VncProxySocketHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(VncProxySocketHandler.class);
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private Socket vncSocket;

    @Override
    public void afterConnectionEstablished(WebSocketSession clientSession) throws Exception {
        // Получаем Spring-контекст, чтобы достать наши сервисы
        WebApplicationContext context = WebApplicationContextUtils.getRequiredWebApplicationContext(
                clientSession.getUri().getPath().contains("/vnc/") ?
                        ((jakarta.servlet.http.HttpServletRequest) clientSession.getAttributes().get("HTTP.REQUEST")).getServletContext() : null
        );
        ContainerManagerService containerManagerService = context.getBean(ContainerManagerService.class);

        String sessionId = (String) clientSession.getAttributes().get("sessionId");

        ActiveSessionsService activeSessionsService = SpringContext.getBean(ActiveSessionsService.class);

        ContainerInfo container = containerManagerService.getActiveContainers().get(
                activeSessionsService.getSession(sessionId).getContainerId()
        );

        if (container == null) {
            log.error("VNC Proxy: Container for session {} not found.", sessionId);
            clientSession.close(CloseStatus.SERVER_ERROR.withReason("Container not found"));
            return;
        }

        // Стандартный порт VNC внутри контейнеров Selenoid - 5900
        this.vncSocket = new Socket(container.getContainerName(), 5900);
        log.info("VNC Proxy: TCP socket connected to {}:5900", container.getContainerName());

        // Поток 1: От VNC-сервера (TCP) к клиенту (WebSocket)
        // Поток 1: От VNC-сервера (TCP) к клиенту (WebSocket)
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
                log.debug("VNC->Client pipe closed, likely due to client disconnect.");
            } finally {
                closeConnections(clientSession);
            }
        });

        // Поток 2: От клиента (WebSocket) к VNC-серверу (TCP)
        executor.submit(() -> {
            try (OutputStream vncOutput = vncSocket.getOutputStream()) {
                // Этот поток будет ждать, пока handleBinaryMessage не запишет данные,
                // но в данной реализации он не нужен, так как запись происходит в другом потоке.
                // Мы оставим его для симметрии и возможного расширения.
                // Здесь можно реализовать логику keep-alive пингов, если потребуется.
                while (!executor.isShutdown()) {
                    Thread.sleep(1000);
                }
            } catch (IOException | InterruptedException e) {
                log.debug("Client->VNC pipe closed.");
            } finally {
                closeConnections(clientSession);
            }
        });
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        // Сообщения от клиента (WebSocket) к VNC-серверу (TCP)
        if (vncSocket != null && vncSocket.isConnected()) {
            try (OutputStream vncOutput = vncSocket.getOutputStream()) {
                vncOutput.write(message.getPayload().array());
                vncOutput.flush();
            } catch (IOException e) {
                log.error("VNC Proxy: Error writing to VNC socket", e);
                closeConnections(session);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        closeConnections(session);
    }

    private void closeConnections(WebSocketSession session) {
        log.info("VNC Proxy: Closing all connections for session {}", session.getId());
        executor.shutdownNow();
        try {
            if (vncSocket != null) vncSocket.close();
        } catch (IOException e) { /* ignore */ }
        try {
            if (session.isOpen()) session.close();
        } catch (IOException e) { /* ignore */ }
    }
}