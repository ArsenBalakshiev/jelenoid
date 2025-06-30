package com.balakshievas.jelenoid.service.playwright;

import com.balakshievas.jelenoid.dto.ContainerInfo;
import com.balakshievas.jelenoid.service.AbstractDockerService;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class PlaywrightDockerService extends AbstractDockerService {

    @Value("${jelenoid.playwright.default_version}")
    private String defaultPlaywrightVersion;

    @Value("${jelenoid.playwright.port}")
    private Integer playwrightPort;

    public PlaywrightDockerService() {
        super(LoggerFactory.getLogger(PlaywrightDockerService.class));
    }

    public ContainerInfo startPlaywrightContainer() {
        return startPlaywrightContainer(defaultPlaywrightVersion);
    }

    public ContainerInfo startPlaywrightContainer(String playwrightVersion) {

        String imageName = "mcr.microsoft.com/playwright:v" + playwrightVersion;

        if (!imageExists(imageName)) {
            throw new RuntimeException("There is no playwright image with name " + imageName);
        }

        String containerName = "jelenoid-playwright-" + UUID.randomUUID().toString().substring(0, 8);

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withInit(true)
                .withIpcMode("host")
                .withCapAdd(Capability.SYS_ADMIN)
                .withNetworkMode(dockerNetworkName);

        String[] cmd = {
                "/bin/sh",
                "-c",
                "npx -y playwright@" + playwrightVersion + " run-server --port %s --host 0.0.0.0"
                        .formatted(playwrightPort)
        };

        CreateContainerResponse container = dockerClient
                .createContainerCmd(imageName)
                .withName(containerName)
                .withHostConfig(hostConfig)
                .withCmd(cmd)
                .withExposedPorts(ExposedPort.tcp(playwrightPort))
                .exec();

        dockerClient.startContainerCmd(container.getId()).exec();
        log.info("Container {} started. Waiting for Playwright service to become available on port {}...",
                container.getId(), playwrightPort);


        if (!waitForOpeningSpecificPort(containerName, playwrightPort)) {
            log.error("Playwright service in container {} did not start. Stopping container.",
                    container.getId());
            stopContainer(container.getId());
            throw new RuntimeException("Could not start Playwright service in container.");
        }

        return new ContainerInfo(container.getId(), containerName);
    }
}
