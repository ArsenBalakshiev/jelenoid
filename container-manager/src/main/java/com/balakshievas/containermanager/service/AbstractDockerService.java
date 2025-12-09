package com.balakshievas.containermanager.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Frame;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.ZipInputStream;

public abstract class AbstractDockerService {

    protected DockerClient dockerClient;
    protected String dockerNetworkName;
    protected int containerStopTimeout;
    protected int containerStartTimeout;
    protected final Logger log;

    protected AbstractDockerService(DockerClient dockerClient,
                                    String dockerNetworkName,
                                    int containerStopTimeout,
                                    int containerStartTimeout,
                                    Logger log) {
        this.dockerClient = dockerClient;
        this.dockerNetworkName = dockerNetworkName;
        this.containerStopTimeout = containerStopTimeout;
        this.containerStartTimeout = containerStartTimeout;
        this.log = log;
    }

    public void stopContainer(String containerId) {
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

    protected boolean waitForOpeningSpecificPort(String containerDomain, Integer containerPort) {
        boolean isServiceReady = false;
        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < containerStartTimeout) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(containerDomain, containerPort), 500);
                log.info("%s service is ready!".formatted(containerDomain));
                isServiceReady = true;
                break;
            } catch (IOException e) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        return isServiceReady;
    }

    public Closeable streamContainerLogs(String containerId, ResultCallback<Frame> callback) {
        return dockerClient.logContainerCmd(containerId)
                .withStdOut(true)
                .withStdErr(true)
                .withTailAll()
                .withFollowStream(true)
                .exec(callback);
    }

    public String copyFileToContainer(String containerId, String base64EncodedZip) throws IOException {
        byte[] zipBytes = Base64.getDecoder().decode(base64EncodedZip);

        String fileName;
        byte[] fileContent;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            var zipEntry = zis.getNextEntry();
            if (zipEntry == null) {
                throw new IOException("Invalid ZIP archive: no entries found.");
            }
            fileName = Path.of(zipEntry.getName()).getFileName().toString();
            fileContent = zis.readAllBytes();
        }

        ByteArrayOutputStream tarOutputStream = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tos = new TarArchiveOutputStream(tarOutputStream)) {
            TarArchiveEntry tarEntry = new TarArchiveEntry(fileName);
            tarEntry.setSize(fileContent.length);
            tos.putArchiveEntry(tarEntry);
            tos.write(fileContent);
            tos.closeArchiveEntry();
        }
        byte[] tarBytes = tarOutputStream.toByteArray();

        dockerClient.copyArchiveToContainerCmd(containerId)
                .withTarInputStream(new ByteArrayInputStream(tarBytes))
                .withRemotePath("/tmp/")
                .exec();

        String filePathInContainer = "/tmp/" + fileName;
        log.info("File {} successfully uploaded to container {} at path {}", fileName, containerId, filePathInContainer);

        return filePathInContainer;
    }

}
