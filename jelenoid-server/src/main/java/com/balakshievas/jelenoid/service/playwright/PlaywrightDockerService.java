package com.balakshievas.jelenoid.service.playwright;

import com.balakshievas.jelenoid.config.TaskExecutorConfig;
import com.balakshievas.jelenoid.dto.ContainerInfo;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

@Service
public class PlaywrightDockerService {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightDockerService.class);

    @Autowired
    private DockerClient dockerClient;

    @Value("${jelenoid.docker.network:jelenoid-net}")
    private String dockerNetworkName;

    @Value("${jelenoid.playwright.version}")
    private String playwrightVersion;

    @Value("${jelenoid.playwright.port}")
    private Integer playwrightPort;

    @Value("${jelenoid.timeouts.session}")
    private long sessionTimeoutMillis;

    @Value("${jelenoid.timeouts.queue}")
    private long queueTimeoutMillis;

    @Value("${jelenoid.timeouts.cleanup}")
    private int containerStopTimeout;

    private final Object lock = new Object();

    private volatile static ContainerInfo playwrightContainer = null;

    public String getPlaywrightAddress() {
        synchronized (lock) {
            if (playwrightContainer == null) {
                startPlaywrightContainer();
            }
        }

        playwrightContainer.updateActivity();
        return "ws://" + playwrightContainer.getContainerName() + ":" + playwrightPort;
    }

    private ContainerInfo startPlaywrightContainer() {
        synchronized (lock) {
            if (playwrightContainer != null) {
                return playwrightContainer;
            }

            String containerName = "jelenoid-playwright";

            ExposedPort tcp = ExposedPort.tcp(playwrightPort);
            Ports portBindings = new Ports();
            portBindings.bind(tcp, Ports.Binding.bindPort(playwrightPort));

            HostConfig hostConfig = HostConfig.newHostConfig()
                    .withInit(true)
                    .withIpcMode("host")
                    .withCapAdd(Capability.SYS_ADMIN)
                    .withPortBindings(portBindings)
                    .withNetworkMode(dockerNetworkName);

            String[] cmd = {
                    "/bin/sh",
                    "-c",
                    "npx -y playwright@" + playwrightVersion + " run-server --port %s --host 0.0.0.0"
                            .formatted(playwrightPort)
            };

            CreateContainerResponse container = dockerClient
                    .createContainerCmd("mcr.microsoft.com/playwright:v" + playwrightVersion)
                    .withName(containerName)
                    .withHostConfig(hostConfig)
                    .withCmd(cmd)
                    .withExposedPorts(tcp)
                    .exec();

            dockerClient.startContainerCmd(container.getId()).exec();
            log.info("Container {} started. Waiting for Playwright service to become available on port {}...",
                    container.getId(), playwrightPort);

            boolean isServiceReady = false;
            long startTime = System.currentTimeMillis();
            long timeoutMillis = 30000;

            while (System.currentTimeMillis() - startTime < timeoutMillis) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(containerName, playwrightPort), 500); // Таймаут на попытку 500мс
                    log.info("Playwright service is ready!");
                    isServiceReady = true;
                    break;
                } catch (IOException e) {
                    try {
                        Thread.sleep(1000); // Пауза перед следующей попыткой
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            if (!isServiceReady) {
                log.error("Playwright service in container {} did not start within {} seconds. Stopping container.",
                        container.getId(), timeoutMillis / 1000);
                stopContainer(container.getId()); // Чистим за собой
                throw new RuntimeException("Could not start Playwright service in container.");
            }

            playwrightContainer = new ContainerInfo(container.getId(), containerName);

            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {}

            return playwrightContainer;
        }
    }

    @Scheduled(fixedRateString = "${jelenoid.timeouts.startup}")
    @Async(TaskExecutorConfig.SESSION_TASK_EXECUTOR)
    public void checkInactiveSessions() {
        synchronized (lock) {
            if (playwrightContainer != null && System.currentTimeMillis() - playwrightContainer.getLastActivity() > sessionTimeoutMillis) {
                log.warn("Playwright {} has timed out. Releasing slot and stopping container.",
                        playwrightContainer.getContainerId());
                stopContainer(playwrightContainer.getContainerId());
                playwrightContainer = null;
            }
        }
    }

    @PreDestroy
    public void cleanup() {
        synchronized (lock) {
            if (playwrightContainer != null) {
                stopContainer(playwrightContainer.getContainerId());
                playwrightContainer = null;
            }
        }
    }

    private void stopContainer(String containerId) {
        try {
            log.info("Stopping and removing container {}", containerId);
            try {
                dockerClient.stopContainerCmd(containerId).withTimeout(containerStopTimeout).exec();
            } catch (NotModifiedException e) {
                log.info("Container {} already stopped", containerId);
            }
            dockerClient.removeContainerCmd(containerId).exec();
        } catch (Exception e) {
            log.error("Failed to stop/remove container {}", containerId, e);
        }
    }
}
