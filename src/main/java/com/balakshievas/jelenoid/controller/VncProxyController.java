package com.balakshievas.jelenoid.controller;

import com.balakshievas.jelenoid.config.SpringContext;
import com.balakshievas.jelenoid.service.ActiveSessionsService;
import com.balakshievas.jelenoid.websocket.VncProxySocketHandler; // Мы создадим его на следующем шаге
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
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

    // Здесь мы могли бы инжектировать сервисы для поиска контейнера,
    // но для простоты передадим их в сам обработчик через атрибуты.

    @GetMapping("/vnc/{sessionId}")
    public void proxyVnc(@PathVariable String sessionId, HttpServletRequest request, HttpServletResponse response) throws Exception {

        ServerHttpRequest httpRequest = new ServletServerHttpRequest(request);
        ServerHttpResponse httpResponse = new ServletServerHttpResponse(response);

        // Создаем обработчик, который будет управлять проксированием
        WebSocketHandler vncHandler = new VncProxySocketHandler();

        // В атрибуты передаем все, что нужно обработчику для работы
        var attributes = new HashMap<String, Object>();
        attributes.put("sessionId", sessionId);

        this.upgradeStrategy.upgrade(
                httpRequest,
                httpResponse,
                null, // selectedProtocol
                Collections.emptyList(), // selectedExtensions
                request.getUserPrincipal(),
                vncHandler,
                attributes
        );
    }
}
