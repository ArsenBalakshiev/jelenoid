package com.balakshievas.jelenoid.service;

import com.balakshievas.jelenoid.dto.ContainerInfo;
import com.balakshievas.jelenoid.dto.SessionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    @Autowired
    private BrowserManagerService browserManagerService;

    @Autowired
    private ContainerManagerService containerManagerService;

    @Autowired
    private RestClient restClient;

    private final Map<String, SessionInfo> activeSessions = new ConcurrentHashMap<>();

    public Map<String, Object> createSession(Map<String, Object> requestBody) {
        String image = findImageForRequest(requestBody);
        if (image == null) {
            return createErrorResponse("Requested environment is not available");
        }

        ContainerInfo containerInfo = containerManagerService.startContainer(image);

        try {
            String createSessionUrl = "http://" + containerInfo.getContainerName() + ":4444/session";
            log.info("Proxying session creation to URL: {}", createSessionUrl);

            ResponseEntity<Map<String, Object>> responseFromContainer = restClient.post()
                    .uri(createSessionUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .toEntity(new ParameterizedTypeReference<>() {});

            Map<String, Object> responseValue = (Map<String, Object>) responseFromContainer.getBody().get("value");
            String remoteSessionId = (String) responseValue.get("sessionId");

            if (remoteSessionId == null) {
                throw new IllegalStateException("Container did not return a session ID");
            }

            String hubSessionId = UUID.randomUUID().toString();
            activeSessions.put(hubSessionId, new SessionInfo(containerInfo.getContainerId(), remoteSessionId));
            log.info("Session created: hubId={} -> remoteId={} in container={}", hubSessionId, remoteSessionId, containerInfo.getContainerId());

            responseValue.put("sessionId", hubSessionId);
            return Map.of("value", responseValue);

        } catch (Exception e) {
            log.error("Failed to create session in container, stopping container...", e);
            containerManagerService.stopContainer(containerInfo.getContainerId());
            throw new RuntimeException("Failed to create session in container", e);
        }
    }

    public void deleteSession(String hubSessionId) {
        SessionInfo sessionInfo = activeSessions.remove(hubSessionId);
        if (sessionInfo != null) {
            log.info("Deleting session: hubId={} -> stopping container={}", hubSessionId, sessionInfo.getContainerId());
            containerManagerService.stopContainer(sessionInfo.getContainerId());
        }
    }

    public ResponseEntity<byte[]> proxyRequest(String hubSessionId, HttpMethod method, String relativePath, HttpHeaders headers, byte[] body) {
        SessionInfo sessionInfo = activeSessions.get(hubSessionId);
        if (sessionInfo == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found: " + hubSessionId);
        }

        ContainerInfo containerInfo = containerManagerService.getActiveContainers().get(sessionInfo.getContainerId());
        if (containerInfo == null) {
            activeSessions.remove(hubSessionId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Container for session " + hubSessionId + " is no longer active.");
        }
        containerInfo.updateActivity();

        String pathForContainer = relativePath.replace(hubSessionId, sessionInfo.getRemoteSessionId());

        URI targetUri = UriComponentsBuilder
                .fromUriString("http://" + containerInfo.getContainerName() + ":4444")
                .path(pathForContainer)
                .build(true)
                .toUri();


        log.debug("Proxying: {} {} -> {}", method, relativePath, targetUri);

        RestClient.RequestBodySpec requestSpec = restClient
                .method(method)
                .uri(targetUri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));

        if (body != null && body.length > 0) {
            requestSpec.body(body);
        }

        return requestSpec.retrieve().toEntity(byte[].class);
    }

    private String findImageForRequest(Map<String, Object> requestBody) {
        Map<String, Object> capabilitiesRequest = (Map<String, Object>) requestBody.get("capabilities");
        Map<String, Object> alwaysMatch = (Map<String, Object>) capabilitiesRequest.getOrDefault("alwaysMatch", Collections.emptyMap());
        List<Map<String, Object>> firstMatch = (List<Map<String, Object>>) capabilitiesRequest.getOrDefault("firstMatch", List.of(Collections.emptyMap()));

        for (Map<String, Object> firstMatchOption : firstMatch) {
            Map<String, Object> mergedCapabilities = new HashMap<>(alwaysMatch);
            mergedCapabilities.putAll(firstMatchOption);
            String browserName = (String) mergedCapabilities.get("browserName");
            String browserVersion = (String) mergedCapabilities.get("browserVersion");
            if (browserName != null) {
                String image = browserManagerService.getImageByBrowserNameAndVersion(browserName, browserVersion);
                if (image != null) {
                    return image;
                }
            }
        }
        return null;
    }

    private Map<String, Object> createErrorResponse(String message) {
        Map<String, Object> errorValue = Map.of("message", message, "error", "session not created");
        return Map.of("status", 13, "value", errorValue);
    }
}
