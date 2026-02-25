package com.balakshievas.containermanager.service;

import com.github.dockerjava.api.DockerClient;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CommonContainerManagerService extends AbstractDockerService {

    public CommonContainerManagerService(DockerClient dockerClient,
                                         @Value("${jelenoid.docker.network:jelenoid-net}") String dockerNetworkName,
                                         @Value("${jelenoid.timeouts.cleanup}") int containerStopTimeout,
                                         @Value("${jelenoid.timeouts.starting_timeout}") int containerStartTimeout) {
        super(dockerClient, dockerNetworkName, containerStopTimeout, containerStartTimeout,
                LoggerFactory.getLogger(CommonContainerManagerService.class));
    }
}
