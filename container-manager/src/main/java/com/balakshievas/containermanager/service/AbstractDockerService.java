package com.balakshievas.containermanager.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Frame;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipEntry;
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

    public boolean stopContainer(String containerId) {
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
            return true;
        } catch (Exception e) {
            log.error("Failed to stop/remove container {}: {}", containerId, e.getMessage());
        } finally {
            log.debug("Container stop permit released for {}.", containerId);
        }
        return false;
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

    public SseEmitter streamContainerLogs(String containerId) {

        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

        ResultCallback<Frame> frameResultCallback = new ResultCallback.Adapter<Frame>() {
            @Override
            public void onNext(Frame item) {
                try {
                    String payload = new String(item.getPayload());
                    if (payload.isBlank()) {
                        return;
                    }
                    emitter.send(payload.trim());
                } catch (IOException e) {
                    onError(e);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                emitter.completeWithError(throwable);
            }

            @Override
            public void onComplete() {
                emitter.complete();
            }
        };

        Closeable containerLogsStream = dockerClient.logContainerCmd(containerId)
                .withStdOut(true)
                .withStdErr(true)
                .withTailAll()
                .withFollowStream(true)
                .exec(frameResultCallback);

        emitter.onCompletion(() -> {
            try {
                containerLogsStream.close();
            } catch (IOException e) {
            }
        });
        emitter.onTimeout(() -> {
            try {
                containerLogsStream.close();
            } catch (IOException e) {
            }
            emitter.complete();
        });

        return emitter;
    }

    public String copyFileToContainer(String containerId, byte[] rawBytes) throws IOException {
        String fileName = null;
        byte[] fileContent = null;

        final long MAX_FILE_SIZE = 50 * 1024 * 1024;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(rawBytes))) {
            ZipEntry zipEntry;
            while ((zipEntry = zis.getNextEntry()) != null) {
                if (zipEntry.isDirectory()) {
                    continue;
                }
                fileName = Path.of(zipEntry.getName()).getFileName().toString();

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int len;
                long totalRead = 0;

                while ((len = zis.read(buffer)) > 0) {
                    totalRead += len;
                    if (totalRead > MAX_FILE_SIZE) {
                        throw new IOException("File exceeds maximum allowed size (Zip Bomb protection).");
                    }
                    baos.write(buffer, 0, len);
                }

                fileContent = baos.toByteArray();
                break;
            }
        }

        if (fileName == null || fileContent == null) {
            throw new IOException("ZIP archive is empty or contains only directories.");
        }

        ByteArrayOutputStream tarOutputStream = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tos = new TarArchiveOutputStream(tarOutputStream)) {
            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);

            TarArchiveEntry tarEntry = new TarArchiveEntry(fileName);
            tarEntry.setSize(fileContent.length);
            tarEntry.setMode(0644);

            tos.putArchiveEntry(tarEntry);
            tos.write(fileContent);
            tos.closeArchiveEntry();
            tos.finish();
        } catch (IOException e) {
            throw new IOException("Failed to create TAR archive", e);
        }

        try {
            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withTarInputStream(new ByteArrayInputStream(tarOutputStream.toByteArray()))
                    .withRemotePath("/")
                    .exec();
        } catch (Exception e) {
            log.error("Docker copy failed for container {}", containerId, e);
            throw new IOException("Failed to copy file to container: " + e.getMessage(), e);
        }

        String filePathInContainer = "/" + fileName;
        log.info("File {} uploaded to {}", fileName, filePathInContainer);

        return filePathInContainer;
    }


}
