package com.balakshievas.jelenoid.controller;

import com.balakshievas.jelenoid.service.SeleniumSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/wd/hub")
public class WdHubSessionController {

    @Autowired
    private SeleniumSessionService seleniumSessionService;

    @PostMapping("/session")
    public CompletableFuture<Map<String, Object>> createSession(@RequestBody Map<String, Object> requestBody) {
        return seleniumSessionService.createSessionOrQueue(requestBody);
    }

    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        seleniumSessionService.deleteSession(sessionId);
        return ResponseEntity.ok().build();
    }

    @RequestMapping("/session/{sessionId}/**")
    public ResponseEntity<Resource> proxy(
            @PathVariable String sessionId,
            HttpMethod method,
            HttpServletRequest request,
            @RequestHeader HttpHeaders headers,
            InputStream bodyStream
    ) {

        String fullPath = request.getRequestURI();
        String relativePath = fullPath.substring("/wd/hub".length());

        return seleniumSessionService.proxyRequest(sessionId, method, relativePath, headers, bodyStream);
    }

    @PostMapping({"/session/{sessionId}/se/file", "/session/{sessionId}/file"})
    public ResponseEntity<Map<String, String>> uploadFile(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> payload) {

        String base64EncodedZip = (String) payload.get("file");
        if (base64EncodedZip == null || base64EncodedZip.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        byte[] fileBytes;
        try {
            fileBytes = Base64.getDecoder().decode(base64EncodedZip);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        String filePathInContainer = seleniumSessionService.uploadFileToSession(sessionId, fileBytes);
        return ResponseEntity.ok(Map.of("value", filePathInContainer));
    }
}
