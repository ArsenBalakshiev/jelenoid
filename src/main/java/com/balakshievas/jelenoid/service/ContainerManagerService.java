package com.balakshievas.jelenoid.service;

import com.balakshievas.jelenoid.dto.ContainerInfo;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.HostConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ContainerManagerService {

    private static final Logger log = LoggerFactory.getLogger(ContainerManagerService.class);

    @Autowired
    private DockerClient dockerClient;

    @Value("${jelenoid.docker.network:jelenoid-net}")
    private String dockerNetworkName;

    @Value("${jelenoid.timeouts.session}")
    private long sessionTimeout;

    @Value("${jelenoid.timeouts.cleanup}")
    private int containerStopTimeout;

    @Value("${jelenoid.video.dir}")
    private String videoOutputDir;

    @Value("${jelenoid.logs.dir}")
    private String logOutputDir;

    @Value("${jelenoid.video.recorder-image}")
    private String videoRecorderImage;

    private final RestClient restClient = RestClient.builder().build();

    public ContainerInfo startContainer(String image, boolean isVncEnabled, boolean isVideoEnabled,
                                        boolean isLogEnabled, String videoName, String logName) {

        String hubSessionId = UUID.randomUUID().toString();

        String containerName = "jelenoid-session" + hubSessionId;

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withNetworkMode(dockerNetworkName)
                .withShmSize(2_147_483_648L);

        List<String> envVars = new ArrayList<>();
        if (isVncEnabled) {
            envVars.add("ENABLE_VNC=true");
        }

        CreateContainerResponse container = dockerClient.createContainerCmd(image)
                .withName(containerName)
                .withHostConfig(hostConfig)
                .withEnv(envVars)
                .exec();

        String containerId = container.getId();
        dockerClient.startContainerCmd(containerId).exec();

        log.info("Started container {} with name {}", container.getId(), containerName);

        waitForContainerToBeReady(containerName);

        return new ContainerInfo(containerId, containerName);
    }

    private void waitForContainerToBeReady(String containerIpAddress) {
        String statusUrl = "http://" + containerIpAddress + ":4444/status";
        long timeout = System.currentTimeMillis() + sessionTimeout;

        log.info("Waiting for container {} to be ready at {}...", containerIpAddress, statusUrl);

        while (System.currentTimeMillis() < timeout) {
            try {
                ResponseEntity<String> response = restClient.get()
                        .uri(statusUrl)
                        .retrieve()
                        .toEntity(String.class);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null && response.getBody().contains("\"ready\":true")) {
                    log.info("Container {} reported ready. Adding a tactical delay of 200ms.", containerIpAddress);
                    Thread.sleep(500);
                    return;
                }
            } catch (Exception e) {
                try {
                    Thread.sleep(1000); // Небольшая пауза перед следующей попыткой
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Health check was interrupted", interruptedException);
                }
            }
        }

        throw new IllegalStateException("Container " + containerIpAddress + " did not become ready in time.");
    }

    public void stopContainer(String containerId) {
        try {
            log.info("Stopping and removing container {}", containerId);
            dockerClient.stopContainerCmd(containerId).withTimeout(containerStopTimeout).exec();
            dockerClient.removeContainerCmd(containerId).exec();
        } catch (Exception e) {
            log.error("Failed to stop/remove container {}", containerId, e);
        }
    }

}