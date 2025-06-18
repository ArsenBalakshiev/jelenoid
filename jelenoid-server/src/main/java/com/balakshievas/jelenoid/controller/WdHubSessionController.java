package com.balakshievas.jelenoid.controller;

import com.balakshievas.jelenoid.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/wd/hub")
public class WdHubSessionController {

    @Autowired
    private SessionService sessionService;

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

    @PostMapping({"/session/{sessionId}/se/file", "/session/{sessionId}/file"})
    public ResponseEntity<Map<String, String>> uploadFile(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> payload) {

        String base64EncodedZip = (String) payload.get("file");
        if (base64EncodedZip == null || base64EncodedZip.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        String filePathInContainer = sessionService.uploadFileToSession(sessionId, base64EncodedZip);

        // WebDriver протокол ожидает в ответе JSON с путем к файлу
        return ResponseEntity.ok(Map.of("value", filePathInContainer));
    }
}
