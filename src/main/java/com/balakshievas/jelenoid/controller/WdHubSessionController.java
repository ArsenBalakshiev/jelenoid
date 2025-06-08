package com.balakshievas.jelenoid.controller;

import com.balakshievas.jelenoid.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/wd/hub/session")
public class WdHubSessionController {

    @Autowired
    private SessionService sessionService; // Новый сервис для управления сессиями

    @PostMapping("")
    public ResponseEntity<Map<String, Object>> createSession(@RequestBody Map<String, Object> requestBody) {
        // Логика извлечения capabilities и создания сессии
        Map<String, Object> w3cResponse = sessionService.createSession(requestBody);
        return ResponseEntity.ok(w3cResponse);
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        sessionService.deleteSession(sessionId);
        return ResponseEntity.ok().build();
    }

    @RequestMapping("/{sessionId}/**")
    public ResponseEntity<byte[]> proxy(
            @PathVariable String sessionId,
            HttpMethod method,
            HttpServletRequest request,
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) byte[] body) {

        String fullPath = request.getRequestURI();
        String relativePath = fullPath.substring("/wd/hub".length());
        return sessionService.proxyRequest(sessionId, method, relativePath, headers, body);
    }
}
