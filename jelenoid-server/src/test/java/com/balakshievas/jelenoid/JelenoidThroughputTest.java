package com.balakshievas.jelenoid;

import org.junit.jupiter.api.*;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.net.MalformedURLException;
import java.net.URL;
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
public class JelenoidThroughputTest {

    private final int ACTIVE_SESSIONS = 10;
    private final int COMMANDS_PER_SESSION = 2000;

    private final URL HUB_URL;
    private final List<WebDriver> activeDrivers = Collections.synchronizedList(new ArrayList<>());

    public JelenoidThroughputTest() throws MalformedURLException {
        this.HUB_URL = new URL("http://localhost:4444/wd/hub");
    }

    @BeforeAll
    public void setupSessions() {
        System.out.println("=== WARMUP: Creating " + ACTIVE_SESSIONS + " active sessions ===");
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < ACTIVE_SESSIONS; i++) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    ChromeOptions options = new ChromeOptions();
                    options.addArguments("--headless", "--disable-gpu");
                    DesiredCapabilities caps = new DesiredCapabilities();
                    caps.setBrowserName("chrome");
                    caps.setCapability(ChromeOptions.CAPABILITY, options);

                    WebDriver driver = new RemoteWebDriver(HUB_URL, caps);
                    driver.get("data:text/html,<html><head><title>Benchmark</title></head><body><h1>OK</h1></body></html>");
                    activeDrivers.add(driver);
                    System.out.print(".");
                } catch (Exception e) {
                    System.err.println("Failed to create session: " + e.getMessage());
                }
            }, executor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        System.out.println("\n=== WARMUP DONE: " + activeDrivers.size() + " sessions ready ===");
    }

    @Test
    @DisplayName("High Frequency Proxy Benchmark (RPS) - Robust")
    public void benchmarkProxyThroughputRobust() {
        Assumptions.assumeFalse(activeDrivers.isEmpty(), "No active drivers created!");

        int iterations = 5; // Количество прогонов

        System.out.println("\n=== 1. JVM WARMUP PHASE (Ignored Results) ===");
        // Прогоняем один раз, чтобы JIT скомпилировал код, но не считаем это в результат
        runSingleBenchmarkIteration("Warmup", COMMANDS_PER_SESSION);

        System.out.println("\n=== 2. MEASUREMENT PHASE ===");
        List<Double> results = new ArrayList<>();

        for (int i = 1; i <= iterations; i++) {
            // Предлагаю увеличить нагрузку для замера, чтобы снизить влияние случайности
            // Например, 2000 команд на сессию вместо 500
            double rps = runSingleBenchmarkIteration("Run #" + i, 1000);
            results.add(rps);

            // Даем системе выдохнуть и GC почистить мусор
            System.gc();
            try { Thread.sleep(1000); } catch (InterruptedException e) {}
        }

        // Считаем среднее
        double avgRps = results.stream().mapToDouble(d -> d).average().orElse(0.0);
        double maxRps = results.stream().mapToDouble(d -> d).max().orElse(0.0);

        System.out.println("\n==================== FINAL ROBUST REPORT ====================");
        System.out.printf("Runs: %d%n", iterations);
        System.out.printf("Average RPS: %.2f req/sec%n", avgRps);
        System.out.printf("Max RPS:     %.2f req/sec%n", maxRps);
        System.out.println("=============================================================");
    }

    private double runSingleBenchmarkIteration(String name, int commandsPerSession) {
        AtomicInteger totalRequests = new AtomicInteger(0);
        ExecutorService benchmarkExecutor = Executors.newVirtualThreadPerTaskExecutor();

        Instant start = Instant.now();

        List<CompletableFuture<Void>> tasks = activeDrivers.stream()
                .map(driver -> CompletableFuture.runAsync(() -> {
                    for (int i = 0; i < commandsPerSession; i++) {
                        try {
                            driver.getTitle();
                            totalRequests.incrementAndGet();
                        } catch (Exception e) { /* ignore */ }
                    }
                }, benchmarkExecutor))
                .toList();

        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();

        Instant end = Instant.now();
        long durationMs = Duration.between(start, end).toMillis();

        double rps = (totalRequests.get() / (durationMs / 1000.0));
        System.out.printf("[%s] Time: %d ms | RPS: %.2f%n", name, durationMs, rps);
        return rps;
    }

    @AfterAll
    public void tearDown() {
        System.out.println("Cleaning up sessions...");
        activeDrivers.parallelStream().forEach(driver -> {
            try {
                driver.quit();
            } catch (Exception e) {
                // ignore
            }
        });
    }

    private void printResults(int totalRequests, long durationMs) {
        double durationSec = durationMs / 1000.0;
        double rps = totalRequests / durationSec;

        System.out.println("\n==================== PROXY BENCHMARK REPORT ====================");
        System.out.printf("Total Requests Processed: %d%n", totalRequests);
        System.out.printf("Total Time:               %d ms%n", durationMs);
        System.out.printf("Throughput (RPS):         %.2f req/sec%n", rps);
        System.out.println("---------------------------------------------------------------");
        System.out.println("Interpretation:");
        System.out.println(" - High RPS means your Java code (Proxy) puts minimal overhead.");
        System.out.println(" - With Virtual Threads, this number should be stable even with 50+ sessions.");
        System.out.println("===============================================================");
    }
}
