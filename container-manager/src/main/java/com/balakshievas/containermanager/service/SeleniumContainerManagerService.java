package com.balakshievas.containermanager.service;

import com.balakshievas.containermanager.dto.ContainerInfo;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.HostConfig;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SeleniumContainerManagerService extends AbstractDockerService {

    private final RestClient restClient = RestClient.builder().build();

    public SeleniumContainerManagerService(DockerClient dockerClient,
                                           @Value("${jelenoid.docker.network:jelenoid-net}") String dockerNetworkName,
                                           @Value("${jelenoid.timeouts.cleanup}") int containerStopTimeout,
                                           @Value("${jelenoid.timeouts.starting_timeout}") int containerStartTimeout) {
        super(dockerClient, dockerNetworkName, containerStopTimeout, containerStartTimeout,
                LoggerFactory.getLogger(SeleniumContainerManagerService.class));
    }

    public ContainerInfo startContainer(String image, boolean isVncEnabled) {

        String hubSessionId = UUID.randomUUID().toString();

        String containerName = "jelenoid-session-" + hubSessionId;

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withNetworkMode(dockerNetworkName)
                .withShmSize(2_147_483_648L)
                .withTmpFs(Map.of("/tmp", "rw,noexec,nosuid,size=1g"));

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
        long timeout = System.currentTimeMillis() + containerStartTimeout;

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
                    Thread.sleep(1000);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Health check was interrupted", interruptedException);
                }
            }
        }

        throw new IllegalStateException("Container " + containerIpAddress + " did not become ready in time.");
    }
}
