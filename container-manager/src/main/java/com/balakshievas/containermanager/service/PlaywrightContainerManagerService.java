package com.balakshievas.containermanager.service;

import com.balakshievas.containermanager.dto.ContainerInfo;
import com.balakshievas.containermanager.exception.NoImageException;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class PlaywrightContainerManagerService extends AbstractDockerService {

    @Value("${jelenoid.playwright.port}")
    private Integer playwrightPort;

    @Value("${jelenoid.playwright.shm-size:4294967296}")
    private Long shmSize;

    @Value("${jelenoid.playwright.tmpfs-size:2g}")
    private String tmpFsSize;

    public PlaywrightContainerManagerService(DockerClient dockerClient,
                                             @Value("${jelenoid.docker.network:jelenoid-net}") String dockerNetworkName,
                                             @Value("${jelenoid.timeouts.cleanup}") int containerStopTimeout,
                                             @Value("${jelenoid.timeouts.starting_timeout}") int containerStartTimeout) {
        super(dockerClient, dockerNetworkName, containerStopTimeout, containerStartTimeout,
                LoggerFactory.getLogger(PlaywrightContainerManagerService.class));
    }

    public ContainerInfo startPlaywrightContainer(String playwrightImage, String playwrightVersion) {

        if (!imageExists(playwrightImage)) {
            throw new NoImageException("There is no playwright image with name " + playwrightImage);
        }

        String containerName = "jelenoid-playwright-" + UUID.randomUUID().toString().substring(0, 8);

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withInit(true)
                .withIpcMode("host")
                .withCapAdd(Capability.SYS_ADMIN)
                .withNetworkMode(dockerNetworkName)
                .withShmSize(shmSize)
                .withTmpFs(Map.of("/tmp", "rw,noexec,nosuid,size=" + tmpFsSize));

        String[] cmd = {
                "npx",
                "-y",
                "playwright@" + playwrightVersion,
                "run-server",
                "--port",
                String.valueOf(playwrightPort),
                "--host",
                "0.0.0.0"
        };

        CreateContainerResponse container = dockerClient
                .createContainerCmd(playwrightImage)
                .withName(containerName)
                .withHostConfig(hostConfig)
                .withCmd(cmd)
                .exec();

        dockerClient.startContainerCmd(container.getId()).exec();
        log.info("Container {} started. Waiting for Playwright service to become available ...",
                container.getId());


        if (!waitForOpeningSpecificPort(containerName, 3000)) {
            log.error("Playwright service in container {} did not start. Stopping container.",
                    container.getId());
            stopContainer(container.getId());
            throw new RuntimeException("Could not start Playwright service in container.");
        }

        return new ContainerInfo(container.getId(), containerName);
    }

}
