package com.balakshievas.tests.playwright;

import com.microsoft.playwright.*;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PlaywrightJelenoidThroughputTest {

    private static final int ACTIVE_SESSIONS = 10;
    private static final int COMMANDS_PER_SESSION = 2000;
    private static final String WS_ENDPOINT = "ws://localhost:4444/playwright";

    @Test
    @DisplayName("Playwright WebSocket Proxy Throughput Benchmark")
    void benchmarkThroughput() {
        System.out.println("Starting stress test: " + (ACTIVE_SESSIONS * COMMANDS_PER_SESSION) + " total commands...");

        runBenchmarkIteration("Warmup", 100);
        runBenchmarkIteration("Measurement", COMMANDS_PER_SESSION);
    }

    private void runBenchmarkIteration(String name, int commandsCount) {
        AtomicInteger totalOps = new AtomicInteger(0);
        Instant start = Instant.now();

        try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> tasks = new ArrayList<>();

            for (int i = 0; i < ACTIVE_SESSIONS; i++) {
                tasks.add(CompletableFuture.runAsync(() -> {
                    try (Playwright pw = Playwright.create();
                         Browser browser = pw.chromium().connect(WS_ENDPOINT,
                                 new BrowserType.ConnectOptions().setTimeout(60000))) {

                        Page page = browser.newPage();
                        page.navigate("data:text/html,<html><head><title>Benchmark</title></head><body><h1>Ready</h1></body></html>");

                        for (int j = 0; j < commandsCount; j++) {
                            try {
                                if (page.title() != null) {
                                    totalOps.incrementAndGet();
                                }
                            } catch (Exception e) { /* ignore */ }
                        }
                    } catch (Exception e) {
                        System.err.println("Session failed: " + e.getMessage());
                    }
                }, virtualExecutor));
            }

            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        }

        Instant end = Instant.now();
        long durationMs = Duration.between(start, end).toMillis();
        double rps = (totalOps.get() / (durationMs / 1000.0));

        System.out.println("\n==================== " + name + " REPORT ====================");
        System.out.printf("Total Commands:       %d%n", totalOps.get());
        System.out.printf("Total Time:           %d ms%n", durationMs);
        System.out.printf("Throughput (RPS):     %.2f ops/sec%n", rps);
        System.out.println("==========================================================");
    }
}
