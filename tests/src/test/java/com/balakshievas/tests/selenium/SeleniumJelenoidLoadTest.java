package com.balakshievas.tests.selenium;

import org.junit.jupiter.api.*;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SeleniumJelenoidLoadTest extends BaseSeleniumJelenoidTest {

    private final int PARALLEL_TESTS = 30;
    private final ConcurrentLinkedQueue<Long> sessionCreationTimes = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> failedTests = new ConcurrentLinkedQueue<>();

    @BeforeAll
    public void beforeAll() {
        sessionCreationTimes.clear();
        failedTests.clear();
        System.out.printf("Starting load test with %d parallel sessions...%n", PARALLEL_TESTS);
    }

    @Test
    @DisplayName("Создание " + PARALLEL_TESTS + " конкурентных сессий через Jelenoid")
    public void runConcurrentLoadTest() {
        // Используем try-with-resources для автоматического закрытия ExecutorService
        try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {

            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (int i = 0; i < PARALLEL_TESTS; i++) {
                final int testNumber = i;
                // КРИТИЧНО: передаем virtualExecutor вторым параметром в runAsync!
                futures.add(CompletableFuture.runAsync(() -> runSingleTest(testNumber), virtualExecutor));
            }

            // Ожидаем завершения всех потоков с таймаутом
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(5, TimeUnit.MINUTES)
                    .join();

        } catch (CompletionException e) {
            Assertions.fail("Load test failed or timed out", e);
        }

        Assertions.assertTrue(failedTests.isEmpty(), "Some tests failed: " + String.join(", ", failedTests));
    }

    private void runSingleTest(int testNumber) {
        String threadName = Thread.currentThread().getName();
        WebDriver driver = null;
        try {
            Instant start = Instant.now();

            ChromeOptions options = new ChromeOptions();
            options.addArguments("--no-sandbox", "--disable-dev-shm-usage", "--headless");
            options.setPageLoadTimeout(Duration.ofSeconds(60));

            // Создаем сессию через базовый класс
            driver = createDriver(options);

            Instant end = Instant.now();
            long timeElapsed = Duration.between(start, end).toMillis();
            sessionCreationTimes.add(timeElapsed);

            System.out.printf("[%s] Test #%d: Session created in %d ms. Session ID: %s%n",
                    threadName, testNumber, timeElapsed, ((RemoteWebDriver) driver).getSessionId());

            driver.get("https://www.google.com");
            Assertions.assertEquals("Google", driver.getTitle());

            // Используем ThreadLocalRandom вместо Math.random() в многопоточной среде
            Thread.sleep(ThreadLocalRandom.current().nextLong(1000, 3000));

        } catch (Exception e) {
            System.err.printf("[%s] Test #%d: FAILED! Reason: %s%n", threadName, testNumber, e.getMessage());
            failedTests.add(String.format("Test #%d failed: %s", testNumber, e.getClass().getSimpleName()));
        } finally {
            if (driver != null) {
                driver.quit(); // В асинхронных тестах закрываем драйвер вручную
            }
        }
    }

    @AfterAll
    public void afterAll() {
        System.out.println("\n==================== LOAD TEST REPORT ====================");
        System.out.printf("Total tests executed: %d%n", PARALLEL_TESTS);
        System.out.printf("Successful sessions created: %d%n", sessionCreationTimes.size());
        System.out.printf("Failed tests: %d%n", failedTests.size());

        if (!sessionCreationTimes.isEmpty()) {
            printStats("Session Creation Time (ms)", new ArrayList<>(sessionCreationTimes));
        }
        System.out.println("==========================================================");
    }

    private void printStats(String name, List<Long> times) {
        Collections.sort(times);
        long min = times.get(0);
        long max = times.get(times.size() - 1);
        double avg = times.stream().mapToLong(Long::longValue).average().orElse(0.0);
        long p95 = times.get((int) (times.size() * 0.95));

        System.out.printf("%nMetrics for '%s':%n", name);
        System.out.printf("  -> Average: %.2f ms%n", avg);
        System.out.printf("  -> Min: %d ms%n", min);
        System.out.printf("  -> Max: %d ms%n", max);
        System.out.printf("  -> 95th Percentile: %d ms%n", p95);
    }
}
