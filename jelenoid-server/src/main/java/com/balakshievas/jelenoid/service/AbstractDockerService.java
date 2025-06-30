package com.balakshievas.jelenoid.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotModifiedException;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.Arrays;

public class AbstractDockerService {

    @Autowired
    protected DockerClient dockerClient;

    @Value("${jelenoid.docker.network:jelenoid-net}")
    private String dockerNetworkName;

    protected final Logger log;

    protected AbstractDockerService(Logger log) {
        this.log = log;
    }

    protected void stopContainer(String containerId, int containerStopTimeout) {
        try {
            log.info("Stopping and removing container {}", containerId);
            try {
                dockerClient.stopContainerCmd(containerId)
                        .withTimeout(containerStopTimeout)
                        .exec();
            } catch (NotModifiedException e) {
                log.info("Container {} already stopped", containerId);
            }
            dockerClient.removeContainerCmd(containerId).exec();
        } catch (Exception e) {
            log.error("Failed to stop/remove container {}: {}", containerId, e.getMessage());
        } finally {
            log.debug("Container stop permit released for {}.", containerId);
        }
    }

    protected boolean imageExists(String imageName) {
        return dockerClient.listImagesCmd()
                .withImageNameFilter(imageName)
                .exec()
                .stream()
                .anyMatch(img -> Arrays.asList(img.getRepoTags()).contains(imageName));
    }

}
