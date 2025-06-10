package com.balakshievas.jelenoid.controller;

import com.balakshievas.jelenoid.dto.Session;
import com.balakshievas.jelenoid.service.ActiveSessionsService;
import com.balakshievas.jelenoid.websocket.VncProxySocketHandler;
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
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.RequestUpgradeStrategy;
import org.springframework.web.socket.server.standard.TomcatRequestUpgradeStrategy;

import java.util.Collections;
import java.util.HashMap;

@RestController
public class VncProxyController {

    private final RequestUpgradeStrategy upgradeStrategy = new TomcatRequestUpgradeStrategy();

    private static final Logger log = LoggerFactory.getLogger(VncProxyController.class);

    @Autowired
    private ActiveSessionsService activeSessionsService;

    @GetMapping("/vnc/{sessionId}")
    public void proxyVnc(@PathVariable String sessionId, HttpServletRequest request, HttpServletResponse response)
            throws Exception {

        ServerHttpRequest httpRequest = new ServletServerHttpRequest(request);
        ServerHttpResponse httpResponse = new ServletServerHttpResponse(response);

        Session session = activeSessionsService.get(sessionId);

        if (session == null) {
            log.error("VNC: Session {} not found.", sessionId);
            response.sendError(HttpStatus.NOT_FOUND.value(), "Session not found");
            return;
        }

        WebSocketHandler vncHandler = new VncProxySocketHandler(session.containerInfo());

        var attributes = new HashMap<String, Object>();
        attributes.put("sessionId", sessionId);

        this.upgradeStrategy.upgrade(
                httpRequest,
                httpResponse,
                null,
                Collections.emptyList(),
                request.getUserPrincipal(),
                vncHandler,
                attributes
        );
    }
}
