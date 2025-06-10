package com.balakshievas.jelenoid.service;

import com.balakshievas.jelenoid.config.TaskExecutorConfig;
import com.balakshievas.jelenoid.dto.ContainerInfo;
import com.balakshievas.jelenoid.dto.PendingRequest;
import com.balakshievas.jelenoid.dto.Session;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    @Value("${jelenoid.limit}")
    private int sessionLimit;
    @Value("${jelenoid.queue.timeout}")
    private long queueTimeoutSeconds;
    @Value("${server.address:localhost}")
    private String serverAddress;
    @Value("${server.port:4444}")
    private int serverPort;

    @Autowired private BrowserManagerService browserManagerService;
    @Autowired private ActiveSessionsService activeSessionsService;
    @Autowired private ContainerManagerService containerManagerService;
    @Autowired private RestClient restClient;
    @Autowired private ObjectMapper objectMapper;
    @Autowired @Qualifier(TaskExecutorConfig.SESSION_TASK_EXECUTOR) private Executor taskExecutor;

    public CompletableFuture<Map<String, Object>> createSessionOrQueue(Map<String, Object> requestBody) {
        if (activeSessionsService.tryReserveSlot()) {
            return CompletableFuture.supplyAsync(() -> createSessionInternal(requestBody), taskExecutor)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Session creation failed, releasing previously reserved slot.");
                            activeSessionsService.releaseSlot();
                            processQueue();
                        }
                    });
        } else {
            log.info("No free slots. Adding request to queue. In-progress: {}/{}, Queue: {}",
                    activeSessionsService.getInProgressCount(), sessionLimit, activeSessionsService.getQueueSize());

            CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
            PendingRequest pendingRequest = new PendingRequest(requestBody, future);
            future.orTimeout(queueTimeoutSeconds, TimeUnit.SECONDS);

            if (activeSessionsService.offerToQueue(pendingRequest)) {
                return future;
            } else {
                log.warn("Queue is full. Rejecting request.");
                return CompletableFuture.failedFuture(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Session queue is full"));
            }
        }
    }

    private Map<String, Object> createSessionInternal(Map<String, Object> requestBody) {
        String image = findImageForRequest(requestBody);

        Map<String, Object> selenoidOptions = findSelenoidOptions(requestBody);

        boolean enableVnc = getBooleanOption(selenoidOptions, "enableVNC");
        boolean enableVideo = getBooleanOption(selenoidOptions, "enableVideo");
        boolean enableLog = getBooleanOption(selenoidOptions, "enableLog");

        String videoName = getStringOption(selenoidOptions, "videoName");
        String logName = getStringOption(selenoidOptions, "logName");

        ContainerInfo containerInfo = null;
        try {
            containerInfo = containerManagerService.startContainer(image, enableVnc, enableVideo, enableLog,
                    videoName, logName);

            String createSessionUrl = "http://" + containerInfo.getContainerName() + ":4444/session";
            ResponseEntity<String> response = restClient.post()
                    .uri(createSessionUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .toEntity(String.class);

            Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), new TypeReference<>() {});
            Map<String, Object> responseValue = (Map<String, Object>) responseBody.get("value");
            String remoteSessionId = (String) responseValue.get("sessionId");

            if (remoteSessionId == null) {
                throw new IllegalStateException("Container did not return a session ID");
            }

            String hubSessionId = UUID.randomUUID().toString();
            Session session = new Session(hubSessionId, remoteSessionId, containerInfo);
            activeSessionsService.sessionSuccessfullyCreated(hubSessionId, session);

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
            if (containerInfo != null) {
                containerManagerService.stopContainer(containerInfo.getContainerId());
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create session in container", e);
        }
    }

    public void deleteSession(String hubSessionId) {
        Session session = activeSessionsService.sessionDeleted(hubSessionId);
        if (session != null) {
            containerManagerService.stopContainer(session.containerInfo().getContainerId());
            processQueue();
        }
    }

    public void processQueue() {
        if (activeSessionsService.getQueueSize() > 0) {
            if (activeSessionsService.tryReserveSlot()) {
                PendingRequest nextRequest = activeSessionsService.pollFromQueue();
                if (nextRequest != null) {
                    log.info("Processing next request from queue...");
                    CompletableFuture.supplyAsync(() -> createSessionInternal(nextRequest.getRequestBody()), taskExecutor)
                            .whenComplete((result, ex) -> {
                                if (ex != null) {
                                    activeSessionsService.releaseSlot();
                                    nextRequest.getFuture().completeExceptionally(ex);
                                    processQueue();
                                } else {
                                    nextRequest.getFuture().complete(result);
                                }
                            });
                } else {
                    activeSessionsService.releaseSlot();
                }
            }
        }
    }

    public ResponseEntity<byte[]> proxyRequest(String hubSessionId, HttpMethod method, String relativePath, HttpHeaders headers, byte[] body) {
        Session session = activeSessionsService.get(hubSessionId);

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
