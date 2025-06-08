package com.balakshievas.superselenoid.service;

import com.balakshievas.superselenoid.dto.ContainerInfo;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.HostConfig;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ContainerManagerService {

    private static final Logger log = LoggerFactory.getLogger(ContainerManagerService.class);

    @Value("${superselenoid.timeouts.session}")
    private long sessionTimeout;

    @Value("${superselenoid.timeouts.cleanup}")
    private int containerStopTimeout;

    @Autowired
    private DockerClient dockerClient;

    @Value("${superselenoid.docker.network:superselenoid-net}")
    private String dockerNetworkName;

    private final RestClient restClient = RestClient.builder().build();

    private final Map<String, ContainerInfo> activeContainers = new ConcurrentHashMap<>();

    public Map<String, ContainerInfo> getActiveContainers() {
        return activeContainers;
    }

    @Scheduled(fixedRateString = "${superselenoid.timeouts.startup}")
    @Async
    public void checkInactiveContainers() {
        activeContainers.entrySet().removeIf(entry -> {
            if (System.currentTimeMillis() - entry.getValue().getLastActivity() > sessionTimeout) {
                stopContainer(entry.getKey());
                return true;
            }
            return false;
        });
    }

    public ContainerInfo startContainer(String image) {
        String containerName = "superselenoid-session" + UUID.randomUUID().toString();

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withNetworkMode(dockerNetworkName)
                .withShmSize(2_147_483_648L);

        CreateContainerResponse container = dockerClient.createContainerCmd(image)
                .withName(containerName)
                .withHostConfig(hostConfig)
                .exec();

        dockerClient.startContainerCmd(container.getId()).exec();
        log.info("Started container {} with name {}", container.getId(), containerName);

        waitForContainerToBeReady(containerName);

        ContainerInfo info = new ContainerInfo(container.getId(), containerName);
        activeContainers.put(container.getId(), info);

        return info;
    }

    private void waitForContainerToBeReady(String containerName) {
        String statusUrl = "http://" + containerName + ":4444/status";
        long timeout = System.currentTimeMillis() + sessionTimeout;

        log.info("Waiting for container {} to be ready at {}...", containerName, statusUrl);

        while (System.currentTimeMillis() < timeout) {
            try {
                ResponseEntity<String> response = restClient.get()
                        .uri(statusUrl)
                        .retrieve()
                        .toEntity(String.class);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null && response.getBody().contains("\"ready\":true")) {
                    log.info("Container {} is ready.", containerName);
                    return;
                }
            } catch (Exception e) {
                try {
                    Thread.sleep(500); // Небольшая пауза перед следующей попыткой
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Health check was interrupted", interruptedException);
                }
            }
        }

        throw new IllegalStateException("Container " + containerName + " did not become ready in time.");
    }

    public void stopContainer(String containerId) {
        try {
            dockerClient.stopContainerCmd(containerId)
                    .withTimeout(containerStopTimeout)
                    .exec();
        } finally {
            dockerClient.removeContainerCmd(containerId).exec();
            activeContainers.remove(containerId);
        }
    }

    @PreDestroy
    public void cleanup() {
        activeContainers.keySet().parallelStream().forEach(id -> {
            try {
                stopContainer(id);
            } catch (Exception e) {
                log.error("Error stopping container {}", id, e);
            }
        });
    }

}