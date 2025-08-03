package com.balakshievas.jelenoid.config;

import io.nats.client.*;
import io.nats.client.api.DiscardPolicy;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

@Configuration
@Slf4j
public class NatsClientAutoConfiguration {

    private static final String STREAM_NAME = "SESSIONS";
    private static final String SUBJECTS = "sessions.*";

    @Value("${jelenoid.nats-server}")
    private String natsServer;

    @Bean(destroyMethod = "close")
    public Connection natsConnection() {
        Options opts = new Options.Builder()
                .server(natsServer)
                .connectionTimeout(Duration.ofSeconds(2))
                .build();

        try {
            Connection conn = Nats.connect(opts);
            try {
                JetStreamManagement jsm = conn.jetStreamManagement();
                if (!jsm.getStreamNames().contains(STREAM_NAME)) {
                    StreamConfiguration config = StreamConfiguration.builder()
                            .name(STREAM_NAME)
                            .subjects(List.of(SUBJECTS))
                            .storageType(StorageType.File)
                            .retentionPolicy(RetentionPolicy.Limits)
                            .discardPolicy(DiscardPolicy.Old)
                            .replicas(1)
                            .build();

                    jsm.addStream(config);
                    log.info("Создан JetStream-стрим '{}'", STREAM_NAME);
                } else {
                    log.info("JetStream-стрим '{}' уже существует", STREAM_NAME);
                }
            } catch (JetStreamApiException e) {
                log.warn("JetStream недоступен: {}", e.getMessage());
            }
            return conn;
        } catch (Exception e) {
            log.warn("Не удалось подключиться к NATS ({}): {}", natsServer, e.getMessage());
            return null;
        }
    }

    @Bean
    public SessionPublisher sessionEventPublisher(@Autowired(required = false) Connection nats) {
        return new SessionPublisher(nats);
    }
}