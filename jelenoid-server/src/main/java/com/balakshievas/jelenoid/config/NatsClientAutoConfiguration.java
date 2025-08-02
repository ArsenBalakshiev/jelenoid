package com.balakshievas.jelenoid.config;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Duration;

@Configuration
public class NatsClientAutoConfiguration {

    @Value("${jelenoid.nats-server}")
    private String natsServer;

    @Bean(destroyMethod = "close")
    public io.nats.client.Connection natsConnection()
            throws IOException, InterruptedException {

        Options opts = new Options.Builder()
                .server(natsServer)
                .connectionTimeout(Duration.ofSeconds(2))
                .build();

        return Nats.connect(opts);
    }

    @Bean
    public SessionPublisher sessionEventPublisher(@Autowired(required = false) Connection nats) {
        return new SessionPublisher(nats);
    }
}