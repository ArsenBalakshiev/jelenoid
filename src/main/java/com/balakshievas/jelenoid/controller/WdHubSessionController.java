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

    @PostMapping("/session") // Эндпоинт для создания сессии
    public Map<String, Object> createSession(@RequestBody Map<String, Object> requestBody) {
        CompletableFuture<Map<String, Object>> sessionFuture = sessionService.createSessionOrQueue(requestBody);
        try {
            return sessionFuture.get(queueTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Request timed out in session queue.");
        } catch (Exception e) {
            // Обработка других ошибок, например, если очередь полна
            if (e.getCause() instanceof ResponseStatusException rse) {
                throw rse;
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create session", e);
        }
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
