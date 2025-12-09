package com.balakshievas.jelenoid;

import com.microsoft.playwright.*;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PlaywrightThroughputTest {

    // Количество активных сессий (равно твоему лимиту в конфиге)
    private static final int ACTIVE_SESSIONS = 10;

    // Количество команд на одну сессию
    private static final int COMMANDS_PER_SESSION = 2000;

    // Адрес твоего вебсокета
    private static final String WS_ENDPOINT = "ws://localhost:4444/playwright";

    // Храним активные объекты, чтобы не пересоздавать их
    private final List<Playwright> playwrights = new ArrayList<>();
    private final List<Browser> browsers = new ArrayList<>();
    private final List<Page> activePages = new ArrayList<>();

    @BeforeAll
    void setupWarmup() {
        System.out.println("=== 1. WARMUP: Creating " + ACTIVE_SESSIONS + " active Playwright sessions ===");

        // Используем Virtual Threads для инициализации
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (int i = 0; i < ACTIVE_SESSIONS; i++) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        // Важно: создаем отдельный инстанс Playwright для чистоты эксперимента
                        Playwright pw = Playwright.create();

                        // Подключаемся к Jelenoid
                        Browser browser = pw.chromium().connect(WS_ENDPOINT,
                                new BrowserType.ConnectOptions().setTimeout(60000)); // Даем время на старт

                        // Открываем контекст и пустую страницу
                        BrowserContext context = browser.newContext();
                        Page page = context.newPage();

                        // Открываем пустую страницу, чтобы было с чем взаимодействовать
                        // Используем data:url, чтобы не зависеть от сети
                        page.navigate("data:text/html,<html><head><title>Benchmark</title></head><body><h1>Ready</h1></body></html>");

                        synchronized (activePages) {
                            playwrights.add(pw);
                            browsers.add(browser);
                            activePages.add(page);
                            System.out.print(".");
                        }
                    } catch (Exception e) {
                        System.err.println("Failed to create session: " + e.getMessage());
                    }
                }, executor));
            }

            // Ждем, пока все браузеры поднимутся
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        System.out.println("\n=== WARMUP DONE: " + activePages.size() + " sessions ready ===");
    }

    @Test
    @DisplayName("Playwright WebSocket Proxy Throughput Benchmark")
    void benchmarkThroughput() {
        Assumptions.assumeTrue(activePages.size() > 0, "No active sessions to test!");

        System.out.println("Starting stress test: " + (activePages.size() * COMMANDS_PER_SESSION) + " total commands...");

        // 1. Предварительный прогон (JIT warmup)
        System.out.println("--- JIT Warmup Phase (Ignored) ---");
        runBenchmarkIteration("Warmup", 100);

        // 2. Основной замер
        System.out.println("--- Measurement Phase ---");
        runBenchmarkIteration("Run #1", COMMANDS_PER_SESSION);

        // Опционально: можно запустить несколько раз и усреднить
    }

    private void runBenchmarkIteration(String name, int commandsCount) {
        AtomicInteger totalOps = new AtomicInteger(0);

        // Используем Virtual Threads для генерации нагрузки
        try (ExecutorService benchmarkExecutor = Executors.newVirtualThreadPerTaskExecutor()) {

            Instant start = Instant.now();

            List<CompletableFuture<Void>> tasks = activePages.stream()
                    .map(page -> CompletableFuture.runAsync(() -> {
                        for (int i = 0; i < commandsCount; i++) {
                            try {
                                // Самая быстрая операция - title() или evaluate
                                // Это проверяет скорость прохождения сообщения через WebSocket туда и обратно
                                String title = page.title();
                                // Или еще быстрее:
                                // page.evaluate("1 + 1");

                                if (title == null) throw new RuntimeException("Null title");
                                totalOps.incrementAndGet();
                            } catch (Exception e) {
                                // Игнорируем ошибки, чтобы не ломать бенчмарк, но пишем в консоль
                                // System.err.println("Op failed: " + e.getMessage());
                            }
                        }
                    }, benchmarkExecutor))
                    .toList();

            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();

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

    @AfterAll
    void tearDown() {
        System.out.println("Cleaning up sessions...");
        for (Playwright pw : playwrights) {
            try {
                pw.close();
            } catch (Exception e) {
                // ignore
            }
        }
    }
}
