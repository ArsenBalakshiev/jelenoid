package com.balakshievas.jelenoid.service.playwright;

import com.balakshievas.jelenoid.dto.ContainerInfo;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;

@Service
public class PlaywrightDockerService {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightDockerService.class);

    private final List<ContainerInfo> activeSessions = new CopyOnWriteArrayList<>();

    private final Semaphore containerStartSemaphore = new Semaphore(3);

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

    public ContainerInfo startPlaywrightContainer() {

        try {
            containerStartSemaphore.acquire();

            String containerName = "jelenoid-playwright-" + UUID.randomUUID().toString().substring(0, 8);

            ExposedPort tcp = ExposedPort.tcp(playwrightPort);
            Ports portBindings = new Ports();
            portBindings.bind(tcp, Ports.Binding.bindPort(playwrightPort));

            HostConfig hostConfig = HostConfig.newHostConfig()
                    .withInit(true)
                    .withIpcMode("host")
                    .withCapAdd(Capability.SYS_ADMIN)
                    //.withPortBindings(portBindings)
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


            if (!waitForServiceReady(containerName, playwrightPort)) {
                log.error("Playwright service in container {} did not start. Stopping container.",
                        container.getId());
                stopContainer(container.getId()); // Чистим за собой
                throw new RuntimeException("Could not start Playwright service in container.");
            }

            ContainerInfo result = new ContainerInfo(container.getId(), containerName);

            //activeSessions.add(result);

            return result;


        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting to start a container", e);
        } finally {
            containerStartSemaphore.release();
            log.debug("Container start permit released.");
        }
    }

   /* @Scheduled(fixedRateString = "${jelenoid.timeouts.startup}")
    @Async(TaskExecutorConfig.SESSION_TASK_EXECUTOR)
    public void checkInactiveSessions() {
        for (ContainerInfo conatiner : activeSessions) {
            if (conatiner != null && System.currentTimeMillis() - conatiner.getLastActivity() > sessionTimeoutMillis) {
                log.warn("Playwright {} has timed out. Releasing slot and stopping container.",
                        conatiner.getContainerId());
                stopContainer(conatiner.getContainerId());
            }
        }
    }

    @PreDestroy
    public void cleanup() {
        for (ContainerInfo container : activeSessions) {
            stopContainer(container.getContainerId());
        }
    }*/

    private boolean waitForServiceReady(String containerDomain, Integer containerPort) {
        boolean isServiceReady = false;
        long startTime = System.currentTimeMillis();
        long timeoutMillis = 60000;

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(containerDomain, containerPort), 500); // Таймаут на попытку 500мс
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

        return isServiceReady;
    }

    public void stopContainer(String containerId) {

        //activeSessions.removeIf(containerInfo -> containerId.equals(containerInfo.getContainerId()));

        try {
            log.info("Stopping and removing container {}", containerId);
            try {
                dockerClient.stopContainerCmd(containerId).withTimeout(containerStopTimeout).exec();
            } catch (NotModifiedException e) {
                log.info("Container {} already stopped", containerId);
            }
            dockerClient.removeContainerCmd(containerId).exec();
        } catch (Exception e) {
            log.error("Failed to stop/remove container {} {}", containerId, e.getMessage());
        } finally {
            log.debug("Container stop permit released for {}.", containerId);
        }
    }
}
