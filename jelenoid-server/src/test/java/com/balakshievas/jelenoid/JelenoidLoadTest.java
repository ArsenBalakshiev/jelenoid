package com.balakshievas.jelenoid;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Execution(ExecutionMode.CONCURRENT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class JelenoidLoadTest {

    private final int PARALLEL_TESTS = 60;
    private final URL HUB_URL;

    // Потокобезопасная коллекция для сбора метрик
    private final ConcurrentLinkedQueue<Long> sessionCreationTimes = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> failedTests = new ConcurrentLinkedQueue<>();

    public JelenoidLoadTest() throws MalformedURLException {
        this.HUB_URL = new URL("http://localhost:4444/wd/hub");
    }

    @BeforeAll
    public void beforeAll() {
        sessionCreationTimes.clear();
        failedTests.clear();
        System.out.printf("Starting load test with %d parallel sessions...%n", PARALLEL_TESTS);
    }

    @Test
    @DisplayName("Jelenoid Concurrent Session Creation Test")
    public void runConcurrentLoadTest() {
        // Используем ForkJoinPool для создания настоящей параллельной нагрузки
        ForkJoinPool customThreadPool = new ForkJoinPool(PARALLEL_TESTS);

        try {
            customThreadPool.submit(() ->
                    IntStream.range(0, PARALLEL_TESTS).parallel().forEach(this::runSingleTest)
            ).get(5, TimeUnit.MINUTES); // Общий таймаут на все тесты
        } catch (Exception e) {
            Assertions.fail("Load test failed with exception", e);
        } finally {
            customThreadPool.shutdown();
        }

        assertTrue(failedTests.isEmpty(), "Some tests failed: " + String.join(", ", failedTests));
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

    private void runSingleTest(int testNumber) {
        String threadName = Thread.currentThread().getName();
        System.out.printf("[%s] Test #%d: Starting...%n", threadName, testNumber);
        WebDriver driver = null;
        try {
            // --- Сбор метрики времени создания сессии ---
            Instant start = Instant.now();

            ChromeOptions options = new ChromeOptions();
            options.addArguments("--no-sandbox", "--disable-dev-shm-usage", "--headless");
            // Установка таймаута на стороне клиента, чтобы он не ждал вечно
            options.setPageLoadTimeout(Duration.ofSeconds(60));

            DesiredCapabilities capabilities = new DesiredCapabilities();
            capabilities.setBrowserName("chrome");
            capabilities.setVersion("133");
            capabilities.setCapability(ChromeOptions.CAPABILITY, options);
            Map<String, Object> selenoidOptions = new HashMap<>();
            selenoidOptions.put("enableVNC", true);

            capabilities.setCapability("selenoid:options", selenoidOptions);

            driver = new RemoteWebDriver(HUB_URL, capabilities);

            Instant end = Instant.now();
            long timeElapsed = Duration.between(start, end).toMillis();
            sessionCreationTimes.add(timeElapsed);
            System.out.printf("[%s] Test #%d: Session created in %d ms. Session ID: %s%n",
                    threadName, testNumber, timeElapsed, ((RemoteWebDriver) driver).getSessionId());

            // --- Более реалистичные действия ---
            driver.get("https://www.google.com");
            assertEquals("Google", driver.getTitle());

            // Имитация работы пользователя
            Thread.sleep(Duration.ofMillis(1000 + (long) (Math.random() * 2000)).toMillis());

            System.out.printf("[%s] Test #%d: Finished successfully.%n", threadName, testNumber);

        } catch (Exception e) {
            System.err.printf("[%s] Test #%d: FAILED! Reason: %s%n", threadName, testNumber, e.getMessage());
            failedTests.add(String.format("Test #%d failed: %s", testNumber, e.getClass().getSimpleName()));
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
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
