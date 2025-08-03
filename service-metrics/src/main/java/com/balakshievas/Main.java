package com.balakshievas;

import io.nats.client.Options;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {

    public static void main(String[] args) throws Exception {

        String natsUrl = System.getenv().getOrDefault("NATS_URL", "nats://localhost:4222");
        String pgUrl = System.getenv().getOrDefault("POSTGRES_URL",
                "jdbc:postgresql://localhost:5432/metrics");
        String pgUser = System.getenv().getOrDefault("POSTGRES_USER", "metrics-user");
        String pgPassword = System.getenv().getOrDefault("POSTGRES_PASSWORD", "metrics-password");

        SessionDao dao = new SessionDao(pgUrl, pgUser, pgPassword);

        Options natsOpts = new Options.Builder()
                .server(natsUrl)
                .connectionTimeout(Duration.ofSeconds(2))
                .maxReconnects(-1)
                .reconnectWait(Duration.ofSeconds(2))
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(dao::close));

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        pool.submit(new NatsConsumer(dao, natsOpts));

        Thread.currentThread().join();
    }
}