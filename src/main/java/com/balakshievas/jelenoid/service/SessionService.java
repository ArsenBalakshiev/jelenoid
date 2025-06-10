package com.balakshievas.jelenoid.service;

import com.balakshievas.jelenoid.dto.ContainerInfo;
import com.balakshievas.jelenoid.dto.PendingRequest;
import com.balakshievas.jelenoid.dto.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    @Autowired
    private BrowserManagerService browserManagerService;

    @Autowired
    private ContainerManagerService containerManagerService;

    @Autowired
    private RestClient restClient;

    @Autowired
    private ActiveSessionsService activeSessionsService;

    @Value("${server.address:localhost}")
    private String serverAddress;

    @Value("${jelenoid.limit}")
    private int sessionLimit;

    @Value("${server.port:4444}")
    private int serverPort;

    public CompletableFuture<Map<String, Object>> createSessionOrQueue(Map<String, Object> requestBody) {
        if (activeSessionsService.getActiveSessionsCount() < sessionLimit) {
            log.info("Slot available. Creating session immediately. Active sessions: {}", activeSessionsService.getActiveSessionsCount());
            return CompletableFuture.supplyAsync(() -> createSessionInternal(requestBody));
        } else {
            CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
            PendingRequest pendingRequest = new PendingRequest(requestBody, future);

            boolean addedToQueue = activeSessionsService.offerToQueue(pendingRequest);
            if (addedToQueue) {
                log.info("No free slots. Request was added to the queue. Active sessions: {}", activeSessionsService.getActiveSessionsCount());
                return future;
            } else {
                log.warn("Queue is full. Rejecting request. Active sessions: {}", activeSessionsService.getActiveSessionsCount());
                return CompletableFuture.failedFuture(
                        new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Session queue is full")
                );
            }
        }
    }

    public Map<String, Object> createSessionInternal(Map<String, Object> requestBody) {
        String image = findImageForRequest(requestBody);
        if (image == null) {
            return createErrorResponse("Requested environment is not available");
        }
        Map<String, Object> selenoidOptions = findSelenoidOptions(requestBody);

        boolean enableVnc = getBooleanOption(selenoidOptions, "enableVNC");
        boolean enableVideo = getBooleanOption(selenoidOptions, "enableVideo");
        boolean enableLog = getBooleanOption(selenoidOptions, "enableLog");

        String videoName = getStringOption(selenoidOptions, "videoName");
        String logName = getStringOption(selenoidOptions, "logName");

        ContainerInfo containerInfo = containerManagerService.startContainer(image, enableVnc,
                enableVideo, enableLog, videoName, logName);
        ;

        try {
            String createSessionUrl = "http://" + containerInfo.getContainerName() + ":4444/session";
            log.info("Proxying session creation to URL: {}", createSessionUrl);

            ResponseEntity<Map<String, Object>> responseFromContainer = restClient.post()
                    .uri(createSessionUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .toEntity(new ParameterizedTypeReference<>() {
                    });

            Map<String, Object> responseValue = (Map<String, Object>) responseFromContainer.getBody().get("value");
            String remoteSessionId = (String) responseValue.get("sessionId");

            if (remoteSessionId == null) {
                throw new IllegalStateException("Container did not return a session ID");
            }

            String hubSessionId = UUID.randomUUID().toString();
            Session session = new Session(hubSessionId, remoteSessionId, containerInfo);
            activeSessionsService.putSession(hubSessionId, session);
            log.info("Session created: hubId={} -> remoteId={} in container={}",
                    session.hubSessionId(), session.remoteSessionId(), session.containerInfo().getContainerId());

            Map<String, Object> containerCapabilities = (Map<String, Object>) responseValue.get("capabilities");
            if (containerCapabilities.containsKey("goog:chromeOptions")) {
                Map<String, Object> chromeOptions = (Map<String, Object>) containerCapabilities.get("goog:chromeOptions");
                if (chromeOptions.remove("debuggerAddress") != null) {
                    log.info("Removed 'debuggerAddress' from capabilities to prevent client-side conflicts.");
                }
            }

            // 2. Добавляем наш правильный WebSocket URL, как и раньше
            String devToolsUrl = String.format("ws://%s:%d/session/%s/se/cdp", serverAddress, serverPort, hubSessionId);
            containerCapabilities.put("se:cdp", devToolsUrl);
            log.info("Advertising 'se:cdp' endpoint: {}", devToolsUrl);

            responseValue.put("sessionId", hubSessionId);

            return Map.of("value", responseValue);

        } catch (Exception e) {
            log.error("Failed to create session in container, stopping container...", e);
            containerManagerService.stopContainer(containerInfo.getContainerId());
            throw new RuntimeException("Failed to create session in container", e);
        }
    }

    public void deleteSession(String hubSessionId) {
        Session session = activeSessionsService.removeSession(hubSessionId); // Получаем полную сессию
        if (session != null) {
            log.info("Deleting session: hubId={} -> stopping container={}", hubSessionId, session.containerInfo().getContainerId());
            containerManagerService.stopContainer(session.containerInfo().getContainerId());
            processQueue();
        }
    }

    public void processQueue() {
        while (activeSessionsService.getActiveSessionsCount() < sessionLimit) {
            PendingRequest nextRequest = activeSessionsService.pollFromQueue();
            if (nextRequest != null) {
                log.info("A slot has been freed. Processing next request from queue.");

                CompletableFuture.supplyAsync(() -> createSessionInternal(nextRequest.getRequestBody()))
                        .thenAccept(result -> nextRequest.getFuture().complete(result))
                        .exceptionally(ex -> {
                            nextRequest.getFuture().completeExceptionally(ex);
                            return null;
                        });

            } else {
                break;
            }
        }
    }

    public ResponseEntity<byte[]> proxyRequest(String hubSessionId, HttpMethod method, String relativePath, HttpHeaders headers, byte[] body) {
        Session session = activeSessionsService.getSession(hubSessionId);

        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found: " + hubSessionId);
        }

        session.updateActivity();

        ContainerInfo containerInfo = session.containerInfo();
        String remoteSessionId = session.remoteSessionId();

        String pathForContainer = relativePath.replace(hubSessionId, remoteSessionId);

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

    private Map<String, Object> findSelenoidOptions(Map<String, Object> requestBody) {
        try {
            Map<String, Object> capabilities = (Map<String, Object>) requestBody.get("capabilities");
            if (capabilities == null) return Collections.emptyMap();

            Map<String, Object> alwaysMatch = (Map<String, Object>) capabilities.get("alwaysMatch");
            if (alwaysMatch != null && alwaysMatch.get("selenoid:options") instanceof Map) {
                return (Map<String, Object>) alwaysMatch.get("selenoid:options");
            }

            List<Map<String, Object>> firstMatchList = (List<Map<String, Object>>) capabilities.get("firstMatch");
            if (firstMatchList != null) {
                for (Map<String, Object> match : firstMatchList) {
                    if (match != null && match.get("selenoid:options") instanceof Map) {
                        return (Map<String, Object>) match.get("selenoid:options");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not parse 'selenoid:options' from capabilities.", e);
        }
        return Collections.emptyMap();
    }

    private boolean getBooleanOption(Map<String, Object> options, String key) {
        Object value = options.get(key);
        return Boolean.TRUE.equals(value);
    }

    private String getStringOption(Map<String, Object> options, String key) {
        Object value = options.get(key);
        return value instanceof String ? (String) value : null;
    }
}
