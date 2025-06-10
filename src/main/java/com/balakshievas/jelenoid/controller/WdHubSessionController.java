package com.balakshievas.jelenoid.controller;

import com.balakshievas.jelenoid.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/wd/hub")
public class WdHubSessionController {

    @Autowired
    private SessionService sessionService;

    @Value("${jelenoid.queue.timeout}") // Таймаут из конфига
    private long queueTimeoutSeconds;

    @PostMapping("/session")
    public CompletableFuture<Map<String, Object>> createSession(@RequestBody Map<String, Object> requestBody) {
        return sessionService.createSessionOrQueue(requestBody);
    }

    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        sessionService.deleteSession(sessionId);
        return ResponseEntity.ok().build();
    }

    @RequestMapping("/session/{sessionId}/**")
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
