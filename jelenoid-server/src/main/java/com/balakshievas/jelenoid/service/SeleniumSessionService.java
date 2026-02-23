package com.balakshievas.jelenoid.service;

import com.balakshievas.jelenoid.config.SessionPublisher;
import com.balakshievas.jelenoid.config.TaskExecutorConfig;
import com.balakshievas.jelenoid.dto.*;
import com.balakshievas.jelenoid.exception.BrowserVersionNotFoundException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static com.balakshievas.jelenoid.utils.Utils.*;

@Service
public class SeleniumSessionService {

    private static final Logger log = LoggerFactory.getLogger(SeleniumSessionService.class);

    @Value("${jelenoid.limit}")
    private int sessionLimit;
    @Value("${server.address:localhost}")
    private String serverAddress;
    @Value("${server.port:4444}")
    private int serverPort;
    @Value("${jelenoid.auth.token:}")
    private String authToken;

    @Autowired
    private BrowserManagerService browserManagerService;
    @Autowired
    private ActiveSessionsService activeSessionsService;
    @Autowired
    private DockerExternalService dockerExternalService;
    @Autowired
    private RestClient restClient;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    @Qualifier(TaskExecutorConfig.SESSION_TASK_EXECUTOR)
    private Executor taskExecutor;
    @Autowired
    private ApplicationEventPublisher publisher;
    @Autowired
    private SessionPublisher sessionEventPublisher;

    @Async
    @Scheduled(fixedRate = 5000)
    public void events() {
        dispatchStatusUpdate();
    }

    public CompletableFuture<Map<String, Object>> createSessionOrQueue(Map<String, Object> requestBody) {

        authorizeSeleniumRequest(requestBody);

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
            PendingRequest pendingRequest = new PendingRequest(requestBody, future, Instant.now(), System.currentTimeMillis());

            if (activeSessionsService.offerToQueue(pendingRequest)) {
                dispatchStatusUpdate();
                return future;
            } else {
                log.warn("Queue is full. Rejecting request.");
                return CompletableFuture.failedFuture(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Session queue is full"));
            }
        }
    }

    private Map<String, Object> createSessionInternal(Map<String, Object> requestBody) {
        BrowserInfo browserInfo = findImageForRequest(requestBody);

        Map<String, Object> selenoidOptions = findSelenoidOptions(requestBody);

        boolean enableVnc = getBooleanOption(selenoidOptions, "enableVNC");

        if (browserInfo == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found images for your browser");
        }

        ContainerInfo containerInfo = null;
        try {
            containerInfo = dockerExternalService.startSeleniumContainer(browserInfo.getDockerImageName(), enableVnc);

            String createSessionUrl = "http://" + containerInfo.getContainerName() + ":4444/session";
            ResponseEntity<String> response = restClient.post()
                    .uri(createSessionUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .toEntity(String.class);

            Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), new TypeReference<>() {
            });
            Map<String, Object> responseValue = getMap(responseBody, "value", Map.of());
            String remoteSessionId = getString(responseValue, "sessionId");

            if (remoteSessionId == null) {
                throw new IllegalStateException("Container did not return a session ID");
            }

            String hubSessionId = UUID.randomUUID().toString();
            SeleniumSession seleniumSession = new SeleniumSession(hubSessionId, remoteSessionId, containerInfo,
                    browserInfo.getName(), browserInfo.getVersion(), enableVnc);
            activeSessionsService.sessionSuccessfullyCreated(hubSessionId, seleniumSession);

            dispatchStatusUpdate();

            Map<String, Object> containerCapabilities = getMap(responseValue, "capabilities", Map.of());
            if (containerCapabilities.containsKey("goog:chromeOptions")) {
                Map<String, Object> chromeOptions = getMap(containerCapabilities, "goog:chromeOptions", Map.of());
                if (chromeOptions.remove("debuggerAddress") != null) {
                    log.info("Removed 'debuggerAddress' from capabilities to prevent client-side conflicts.");
                }
            }

            String devToolsUrl = String.format("ws://%s:%d/session/%s/se/cdp", serverAddress, serverPort, hubSessionId);
            containerCapabilities.put("se:cdp", devToolsUrl);
            log.info("Advertising 'se:cdp' endpoint: {}", devToolsUrl);

            responseValue.put("sessionId", hubSessionId);

            SessionInfo sessionInfo = sessionEventPublisher
                    .createSessionAndPublish("selenium", browserInfo.getVersion());
            seleniumSession.setSessionInfo(sessionInfo);

            return Map.of("value", responseValue);

        } catch (Exception e) {
            if (containerInfo != null) {
                dockerExternalService.stopContainer(containerInfo.getContainerId());
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to create session in container", e);
        }
    }

    public void deleteSession(String hubSessionId) {
        SeleniumSession seleniumSession = activeSessionsService.sessionDeleted(hubSessionId);
        dispatchStatusUpdate();
        if (seleniumSession != null) {
            dockerExternalService.stopContainer(seleniumSession.getContainerInfo().getContainerId());
            sessionEventPublisher.endSessionByRemoteAndPublish(seleniumSession.getSessionInfo());
            processQueue();
        }
    }

    public void processQueue() {
        if (activeSessionsService.getQueueSize() > 0) {
            if (activeSessionsService.tryReserveSlot()) {
                PendingRequest nextRequest = activeSessionsService.pollFromQueue();
                dispatchStatusUpdate();
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

    public ResponseEntity<Resource> proxyRequest(String hubSessionId, HttpMethod method, String relativePath,
                                                 HttpHeaders headers, InputStream bodyStream) {
        SeleniumSession seleniumSession = activeSessionsService.get(hubSessionId);

        if (seleniumSession == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found: " + hubSessionId);
        }

        seleniumSession.updateActivity();

        ContainerInfo containerInfo = seleniumSession.getContainerInfo();
        String pathForContainer = relativePath.replace(hubSessionId, seleniumSession.getRemoteSessionId());

        URI targetUri = UriComponentsBuilder
                .fromUriString("http://" + containerInfo.getContainerName() + ":4444")
                .path(pathForContainer)
                .build(true)
                .toUri();

        var requestSpec = restClient
                .method(method)
                .uri(targetUri)
                .headers(httpHeaders -> headers.forEach((key, values) -> {
                    if (!isRestrictedHeader(key)) {
                        httpHeaders.addAll(key, values);
                    }
                }));

        if (bodyStream != null && method != HttpMethod.GET) {
            requestSpec.body(new InputStreamResource(bodyStream));
        }

        return requestSpec.exchange((req, res) -> {
            byte[] bodyBytes = res.getBody().readAllBytes();

            return ResponseEntity.status(res.getStatusCode())
                    .headers(res.getHeaders())
                    .body(new ByteArrayResource(bodyBytes));
        });
    }

    private boolean isRestrictedHeader(String headerName) {
        String name = headerName.toLowerCase();
        return name.equals("content-length") ||
                name.equals("transfer-encoding") ||
                name.equals("host") ||
                name.equals("connection") ||
                name.equals("upgrade");
    }

    public String uploadFileToSession(String hubSessionId, byte[] fileBytes) {
        SeleniumSession seleniumSession = activeSessionsService.get(hubSessionId);
        if (seleniumSession == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found: " + hubSessionId);
        }

        return dockerExternalService
                .copyFileToContainer(seleniumSession.getContainerInfo().getContainerId(), fileBytes);
    }

    public StreamingResponseBody streamLogsForSession(String hubSessionId) {
        SeleniumSession seleniumSession = activeSessionsService.get(hubSessionId);
        if (seleniumSession == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found: " + hubSessionId);
        }
        return dockerExternalService.streamContainerLogs(seleniumSession.getContainerInfo().getContainerId());
    }

    private void dispatchStatusUpdate() {
        publisher.publishEvent(new StatusChangedEvent());
    }

    private BrowserInfo findImageForRequest(Map<String, Object> requestBody) {
        Map<String, Object> capabilitiesRequest = getMap(requestBody, "capabilities", Map.of());
        Map<String, Object> alwaysMatch = getMap(capabilitiesRequest, "alwaysMatch", Map.of());
        List<Map<String, Object>> firstMatch = getListOfMaps(capabilitiesRequest, "firstMatch", List.of());

        for (Map<String, Object> firstMatchOption : firstMatch) {
            Map<String, Object> mergedCapabilities = new HashMap<>(alwaysMatch);
            mergedCapabilities.putAll(firstMatchOption);
            String browserName = (String) mergedCapabilities.get("browserName");
            String browserVersion = (String) mergedCapabilities.get("browserVersion");
            if (browserName != null) {
                String image = browserManagerService.getImageByBrowserNameAndVersion(browserName, browserVersion);
                if (image != null) {
                    if (browserVersion == null) {
                        return new BrowserInfo(browserName, null, image, true);
                    } else {
                        return new BrowserInfo(browserName, browserVersion, image, false);
                    }
                } else {
                    throw new BrowserVersionNotFoundException(browserName, browserVersion);
                }
            }
        }
        return null;
    }

    private Map<String, Object> findSelenoidOptions(Map<String, Object> requestBody) {
        try {
            Map<String, Object> capabilities = getMap(requestBody, "capabilities", Map.of());
            if (capabilities == null) return Collections.emptyMap();

            Map<String, Object> alwaysMatch = getMap(capabilities, "alwaysMatch", Map.of());
            if (alwaysMatch != null && alwaysMatch.get("selenoid:options") instanceof Map) {
                return getMap(alwaysMatch, "selenoid:options", Map.of());
            }

            List<Map<String, Object>> firstMatchList = getListOfMaps(capabilities, "firstMatch", List.of());
            for (Map<String, Object> match : firstMatchList) {
                if (match != null && match.get("selenoid:options") instanceof Map) {
                    return getMap(match, "selenoid:options", Map.of());
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

    private void authorizeSeleniumRequest(Map<String, Object> requestBody) {
        if (authToken == null || authToken.isBlank()) {
            return;
        }

        String token = null;
        Map<String, Object> capabilities = getMap(requestBody, "capabilities", null);

        if (capabilities != null) {
            Map<String, Object> alwaysMatch = getMap(capabilities, "alwaysMatch", null);
            if (alwaysMatch != null && alwaysMatch.get("selenoid:options") instanceof Map) {
                Map<String, Object> options = (Map<String, Object>) alwaysMatch.get("selenoid:options");
                token = (String) options.remove("jelenoidToken");
            } else {
                List<Map<String, Object>> firstMatch = getListOfMaps(capabilities, "firstMatch", List.of());
                for (Map<String, Object> match : firstMatch) {
                    if (match != null && match.get("selenoid:options") instanceof Map) {
                        Map<String, Object> options = (Map<String, Object>) match.get("selenoid:options");
                        if (options.containsKey("jelenoidToken")) {
                            token = (String) options.remove("jelenoidToken");
                            break;
                        }
                    }
                }
            }
        }

        if (!authToken.equals(token)) {
            log.warn("Unauthorized Selenium connection attempt.");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or missing jelenoidToken in selenoid:options");
        }
    }

}
