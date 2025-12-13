package com.balakshievas.jelenoid.service;

import com.balakshievas.jelenoid.dto.ContainerInfo;
import com.balakshievas.jelenoid.dto.ContainerInfoRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;

@Service
public class DockerExternalService {

    @Qualifier(value = "jelenoidRestClient")
    @Autowired
    private RestClient restClient;

    public ContainerInfo startSeleniumContainer(String image, Boolean isVncEnabled) {
        ContainerInfoRecord containerInfoRecord = restClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/containers/selenium")
                        .queryParam("image", image)
                        .queryParam("isVncEnabled", isVncEnabled)
                        .build())
                .retrieve()
                .body(ContainerInfoRecord.class);

        if (containerInfoRecord == null) {
            throw new RuntimeException("Could not start playwright container");
        }

        return new ContainerInfo(containerInfoRecord.containerId(), containerInfoRecord.containerName());
    }

    public ContainerInfo startPlaywrightContainer(String image) {
        ContainerInfoRecord containerInfoRecord = restClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/containers/playwright")
                        .queryParam("image", image)
                        .build())
                .retrieve()
                .body(ContainerInfoRecord.class);

        if (containerInfoRecord == null) {
            throw new RuntimeException("Could not start playwright container");
        }

        return new ContainerInfo(containerInfoRecord.containerId(), containerInfoRecord.containerName());
    }

    public Boolean stopContainer(String containerId) {
        return restClient.delete()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/containers")
                        .queryParam("containerId", containerId)
                        .build())
                .retrieve()
                .body(Boolean.class);
    }

    public String copyFileToContainer(String containerId, byte[] fileBytes) {
        ByteArrayResource fileResource = new ByteArrayResource(fileBytes) {
            @Override
            public String getFilename() {
                return "upload.zip";
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource);

        return restClient.post()
                .uri("/api/containers/{containerId}/file", containerId)
                .contentType(MediaType.MULTIPART_FORM_DATA) // Указываем тип контента
                .body(body)
                .retrieve()
                .body(String.class);
    }

    /*public SseEmitter streamContainerLogs(String containerId) {
        SseEmitter clientEmitter = new SseEmitter(Long.MAX_VALUE);
        Thread.ofVirtual().start(() -> {
            try {
                restClient.get()
                        .uri("/api/containers/" + containerId + "/logs")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .exchange((request, response) -> {
                            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.getBody()))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    if (line.startsWith("data:")) {
                                        clientEmitter.send(line.substring(5));
                                    }
                                }
                            }
                            return null;
                        });
                clientEmitter.complete();
            } catch (Exception e) {
                clientEmitter.completeWithError(e);
            }
        });"/api/containers/" + containerId + "/logs"
        return clientEmitter;
    }*/

    public StreamingResponseBody streamContainerLogs(String containerId) {
        return outputStream -> {
            try {
                restClient.get()
                        .uri("/api/containers/" + containerId + "/logs")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .exchange((req, resp) -> {
                            // Проверка статуса
                            if (resp.getStatusCode().isError()) {
                                throw new RuntimeException("Upstream Error: " + resp.getStatusCode());
                            }

                            InputStream inputStream = resp.getBody();

                            // ВАЖНО: Используем маленький буфер, чтобы данные летели сразу
                            byte[] buffer = new byte[1024];
                            int bytesRead;

                            // Читаем из источника
                            while ((bytesRead = inputStream.read(buffer)) != -1) {
                                // Пишем клиенту
                                outputStream.write(buffer, 0, bytesRead);

                                // КРИТИЧНО: Принудительно отправляем данные в сеть
                                outputStream.flush();
                            }
                            return null;
                        });
            } catch (Exception e) {
                throw e;
            }
        };
    }
}
