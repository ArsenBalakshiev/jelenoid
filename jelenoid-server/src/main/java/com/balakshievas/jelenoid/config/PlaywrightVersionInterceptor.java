package com.balakshievas.jelenoid.config;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

public class PlaywrightVersionInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest req, ServerHttpResponse res,
                                   WebSocketHandler h, Map<String,Object> attrs) {
        String path = req.getURI().getPath();
        int idx = path.lastIndexOf('-');
        if (idx > 0 && path.startsWith("/playwright-")) {
            attrs.put("playwrightVersion", path.substring(idx+1));
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {

    }
}
