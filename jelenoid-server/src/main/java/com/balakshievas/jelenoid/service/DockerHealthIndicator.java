package com.balakshievas.jelenoid.service;

import com.github.dockerjava.api.DockerClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("docker")
public class DockerHealthIndicator implements HealthIndicator {

    private final DockerClient dockerClient;

    public DockerHealthIndicator(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    @Override
    public Health health() {
        try {
            dockerClient.pingCmd().exec();
            return Health.up()
                    .withDetail("docker_version", dockerClient.versionCmd().exec().getVersion())
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}