package com.balakshievas.jelenoid.controller;

import com.balakshievas.jelenoid.dto.ContainerInfo;
import com.balakshievas.jelenoid.dto.SessionInfo;
import com.balakshievas.jelenoid.service.ActiveSessionsService;
import com.balakshievas.jelenoid.service.ContainerManagerService;
import com.balakshievas.jelenoid.websocket.DevToolsProxySocketHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.RequestUpgradeStrategy;
import org.springframework.web.socket.server.standard.TomcatRequestUpgradeStrategy;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.security.Principal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class DevToolsProxyController {

    private static final Logger log = LoggerFactory.getLogger(DevToolsProxyController.class);

    private final ActiveSessionsService activeSessionsService;
    private final ContainerManagerService containerManagerService;
    private final RequestUpgradeStrategy upgradeStrategy = new TomcatRequestUpgradeStrategy();

    public DevToolsProxyController(ActiveSessionsService activeSessionsService, ContainerManagerService containerManagerService) {
        this.activeSessionsService = activeSessionsService;
        this.containerManagerService = containerManagerService;
    }

    @GetMapping("/session/{sessionId}/se/cdp")
    public void proxyDevTools(@PathVariable String sessionId, HttpServletRequest request, HttpServletResponse response) throws Exception {

        SessionInfo sessionInfo = activeSessionsService.getSession(sessionId);
        if (sessionInfo == null) {
            response.sendError(HttpStatus.NOT_FOUND.value(), "Session not found");
            return;
        }

        ContainerInfo containerInfo = containerManagerService.getActiveContainers().get(sessionInfo.getContainerId());
        if (containerInfo == null) {
            response.sendError(HttpStatus.NOT_FOUND.value(), "Container for session not found");
            return;
        }

        // 1. Формируем целевой URI для DevTools внутри контейнера
        URI targetUri = UriComponentsBuilder.fromUriString("ws://" + containerInfo.getContainerName() + ":7070")
                .path("/devtools/page/" + sessionInfo.getRemoteSessionId())
                .build().toUri();

        log.info("Attempting to upgrade request for session {} to WebSocket, proxying to {}", sessionId, targetUri);

        // 2. Создаем обработчик, который будет проксировать данные
        // Этот обработчик будет управлять двумя сокетами: клиентским и серверным (к контейнеру)
        WebSocketHandler proxyHandler = new DevToolsProxySocketHandler(targetUri);

        ServerHttpRequest httpRequest = new ServletServerHttpRequest(request);
        ServerHttpResponse httpResponse = new ServletServerHttpResponse(response);

        // 3: Протокол (может быть null)
        String selectedProtocol = httpRequest.getHeaders().getFirst("Sec-WebSocket-Protocol");

        // 4: Расширения (для проксирования просто передаем пустой список)
        List<WebSocketExtension> selectedExtensions = Collections.emptyList();

        // 5: Пользователь (Principal), если используется Spring Security (у вас, скорее всего, null)
        Principal user = request.getUserPrincipal();

        // 6: Наш обработчик, который будет управлять проксированием
        WebSocketHandler wsHandler = proxyHandler;

        // 7: Атрибуты для передачи в сессию (не используются в нашем случае)
        Map<String, Object> attributes = new HashMap<>();

        // Финальный вызов с правильной сигнатурой
        this.upgradeStrategy.upgrade(httpRequest, httpResponse, selectedProtocol, selectedExtensions, user, wsHandler, attributes);
    }
}