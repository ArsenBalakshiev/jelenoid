package com.balakshievas.tests.selenium;

import org.junit.jupiter.api.*;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SeleniumJelenoidThroughputTest extends BaseSeleniumJelenoidTest {

    private final int ACTIVE_SESSIONS = 10;
    private final int COMMANDS_PER_SESSION = 2000;
    private final List<WebDriver> activeDrivers = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    public void setupSessions() {
        System.out.println("=== WARMUP: Creating " + ACTIVE_SESSIONS + " active sessions ===");

        try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (int i = 0; i < ACTIVE_SESSIONS; i++) {
                // Явно передаем virtualExecutor
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        ChromeOptions options = new ChromeOptions();
                        options.addArguments("--headless", "--disable-gpu");

                        WebDriver driver = createDriver(options);
                        driver.get("data:text/html,<html><head><title>Benchmark</title></head><body><h1>OK</h1></body></html>");

                        activeDrivers.add(driver);
                        System.out.print(".");
                    } catch (Exception e) {
                        System.err.println("Failed to create session: " + e.getMessage());
                    }
                }, virtualExecutor));
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
        System.out.println("\n=== WARMUP DONE: " + activeDrivers.size() + " sessions ready ===");
    }

    @Test
    @DisplayName("Замер максимального RPS (Robus Benchmark)")
    public void benchmarkProxyThroughputRobust() {
        Assumptions.assumeFalse(activeDrivers.isEmpty(), "No active drivers created!");

        int iterations = 5;

        System.out.println("\n=== 1. JVM WARMUP PHASE (Ignored Results) ===");
        runSingleBenchmarkIteration("Warmup", COMMANDS_PER_SESSION);

        System.out.println("\n=== 2. MEASUREMENT PHASE ===");
        List<Double> results = new ArrayList<>();

        for (int i = 1; i <= iterations; i++) {
            double rps = runSingleBenchmarkIteration("Run #" + i, COMMANDS_PER_SESSION);
            results.add(rps);

            System.gc();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        double avgRps = results.stream().mapToDouble(d -> d).average().orElse(0.0);
        double maxRps = results.stream().mapToDouble(d -> d).max().orElse(0.0);

        System.out.println("\n==================== FINAL ROBUST REPORT ====================");
        System.out.printf("Runs: %d%n", iterations);
        System.out.printf("Commands per session: %d%n", COMMANDS_PER_SESSION);
        System.out.printf("Average RPS: %.2f req/sec%n", avgRps);
        System.out.printf("Max RPS:     %.2f req/sec%n", maxRps);
        System.out.println("=============================================================");
    }

    private double runSingleBenchmarkIteration(String name, int commandsPerSession) {
        AtomicInteger totalRequests = new AtomicInteger(0);

        Instant start = Instant.now();

        try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> tasks = activeDrivers.stream()
                    .map(driver -> CompletableFuture.runAsync(() -> {
                        for (int i = 0; i < commandsPerSession; i++) {
                            try {
                                driver.getTitle();
                                totalRequests.incrementAndGet();
                            } catch (Exception e) { /* ignore to not affect RPS metrics */ }
                        }
                    }, virtualExecutor))
                    .toList();

            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        }

        Instant end = Instant.now();
        long durationMs = Duration.between(start, end).toMillis();

        double rps = (totalRequests.get() / (durationMs / 1000.0));
        System.out.printf("[%s] Time: %d ms | RPS: %.2f%n", name, durationMs, rps);
        return rps;
    }

    @AfterAll
    public void tearDown() {
        System.out.println("\nCleaning up sessions...");
        activeDrivers.parallelStream().forEach(driver -> {
            try {
                driver.quit();
            } catch (Exception e) {
                // ignore
            }
        });
    }
}
