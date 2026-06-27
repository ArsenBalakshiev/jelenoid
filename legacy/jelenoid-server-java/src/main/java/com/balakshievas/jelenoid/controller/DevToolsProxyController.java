package com.balakshievas.jelenoid.controller;

import com.balakshievas.jelenoid.dto.ContainerInfo;
import com.balakshievas.jelenoid.dto.SeleniumSession;
import com.balakshievas.jelenoid.service.ActiveSessionsService;
import com.balakshievas.jelenoid.websocket.DevToolsProxySocketHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private ActiveSessionsService activeSessionsService;

    private final RequestUpgradeStrategy upgradeStrategy = new TomcatRequestUpgradeStrategy();


    @GetMapping("/session/{sessionId}/se/cdp")
    public void proxyDevTools(@PathVariable String sessionId, HttpServletRequest request, HttpServletResponse response)
            throws Exception {

        SeleniumSession seleniumSession = activeSessionsService.get(sessionId);
        if (seleniumSession == null) {
            response.sendError(HttpStatus.NOT_FOUND.value(), "Session not found");
            return;
        }

        ContainerInfo containerInfo = seleniumSession.getContainerInfo();
        if (containerInfo == null) {
            response.sendError(HttpStatus.NOT_FOUND.value(), "Container for session not found");
            return;
        }

        URI targetUri = UriComponentsBuilder.fromUriString("ws://" + containerInfo.getContainerName() + ":7070")
                .path("/devtools/page/" + seleniumSession.getRemoteSessionId())
                .build().toUri();

        log.info("Attempting to upgrade request for session {} to WebSocket, proxying to {}", sessionId, targetUri);

        WebSocketHandler proxyHandler = new DevToolsProxySocketHandler(targetUri);

        ServerHttpRequest httpRequest = new ServletServerHttpRequest(request);
        ServerHttpResponse httpResponse = new ServletServerHttpResponse(response);

        String selectedProtocol = httpRequest.getHeaders().getFirst("Sec-WebSocket-Protocol");

        List<WebSocketExtension> selectedExtensions = Collections.emptyList();

        Principal user = request.getUserPrincipal();

        WebSocketHandler wsHandler = proxyHandler;

        Map<String, Object> attributes = new HashMap<>();

        this.upgradeStrategy.upgrade(httpRequest, httpResponse, selectedProtocol,
                selectedExtensions, user, wsHandler, attributes);
    }
}