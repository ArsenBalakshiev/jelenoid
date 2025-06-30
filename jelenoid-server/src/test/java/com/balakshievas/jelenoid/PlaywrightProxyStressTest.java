package com.balakshievas.jelenoid;

import com.microsoft.playwright.*;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class PlaywrightProxyStressTest {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightProxyStressTest.class);

    private static final int TOTAL_SESSIONS_TO_TEST = 50;
    private static final String PROXY_WS_URL = "ws://localhost:4444/playwright";

    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        executorService = Executors.newFixedThreadPool(TOTAL_SESSIONS_TO_TEST);
    }

    @AfterEach
    void tearDown() {
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    @Test
    @DisplayName("Стресс-тест прокси с полной изоляцией сессий")
    void testProxySessionLimitAndQueue() {
        log.info("Запуск теста: {} одновременных сессий при лимите в 10", TOTAL_SESSIONS_TO_TEST);

        List<Future<Boolean>> futures = new ArrayList<>();
        AtomicInteger taskCounter = new AtomicInteger(0);

        for (int i = 0; i < TOTAL_SESSIONS_TO_TEST; i++) {
            Callable<Boolean> task = () -> {
                int taskId = taskCounter.incrementAndGet();
                log.info("Задача {} -> Старт.", taskId);

                try (Playwright playwright = Playwright.create()) {
                    log.info("Задача {} -> Попытка подключения...", taskId);

                    try (Browser browser = playwright.chromium().connect(PROXY_WS_URL,
                            new BrowserType.ConnectOptions().setTimeout(900000))) {

                        log.info("Задача {} -> Подключение успешно. Открытие страницы...", taskId);
                        Page page = browser.newPage();
                        page.navigate("http://host.docker.internal:8080/");

                        int waitSec = ThreadLocalRandom.current().nextInt(10, 31);
                        try {
                            Thread.sleep(waitSec * 1_000L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }

                        String title = page.title();
                        Assertions.assertTrue(title.contains("Welcome to nginx!"),
                                "Заголовок должен содержать 'Welcome to nginx!'");
                        log.info("Задача {} -> Успешно завершена.", taskId);
                        return true;

                    } catch (Exception e) {
                        log.error("Задача {} -> Провалена с ошибкой: {}", taskId, e.getMessage(), e);
                        return false;
                    }
                }
            };
            futures.add(executorService.submit(task));
        }

        int successfulSessions = 0;
        for (Future<Boolean> future : futures) {
            try {
                if (future.get(120, TimeUnit.SECONDS)) {
                    successfulSessions++;
                }
            } catch (Exception e) {
                log.error("Не удалось получить результат задачи: {}", e.getMessage(), e);
            }
        }

        log.info("--- Результаты теста ---");
        log.info("Успешно выполненных сессий: {} из {}", successfulSessions, TOTAL_SESSIONS_TO_TEST);

        Assertions.assertEquals(TOTAL_SESSIONS_TO_TEST, successfulSessions,
                "Все сессии должны были успешно завершиться, что доказывает работу очереди.");
    }
}
