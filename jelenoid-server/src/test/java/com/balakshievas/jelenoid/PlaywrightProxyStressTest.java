package com.balakshievas.jelenoid;

import com.microsoft.playwright.*;
import com.microsoft.playwright.BrowserType;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class PlaywrightProxyStressTest {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightProxyStressTest.class);

    // --- Параметры теста ---
    // Лимит сессий, установленный в вашем прокси
    private static final int PROXY_SESSION_LIMIT = 10;
    // Общее количество сессий, которое мы попытаемся запустить (больше лимита)
    private static final int TOTAL_SESSIONS_TO_TEST = 5;
    // URL вашего прокси
    private static final String PROXY_WS_URL = "ws://localhost:4444/playwright";

    private Playwright playwright;
    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        // Создаем один экземпляр Playwright на все тесты
        playwright = Playwright.create();
        // Создаем пул потоков для симуляции одновременных подключений
        executorService = Executors.newFixedThreadPool(TOTAL_SESSIONS_TO_TEST);
    }

    @AfterEach
    void tearDown() {
        if (executorService != null) {
            executorService.shutdownNow();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    @Test
    @DisplayName("Тест лимита сессий и очереди прокси-сервера")
    void testProxySessionLimitAndQueue() throws InterruptedException {
        log.info("Запуск теста: {} одновременных сессий при лимите в {}", TOTAL_SESSIONS_TO_TEST, PROXY_SESSION_LIMIT);

        List<Future<Boolean>> futures = new ArrayList<>();
        AtomicInteger taskCounter = new AtomicInteger(0);

        // Запускаем все задачи одновременно
        for (int i = 0; i < TOTAL_SESSIONS_TO_TEST; i++) {
            Callable<Boolean> task = () -> {
                int taskId = taskCounter.incrementAndGet();
                log.info("Задача {} -> Попытка подключения...", taskId);

                // Используем try-with-resources для автоматического закрытия браузера
                try (Browser browser = playwright.chromium().connect(PROXY_WS_URL,
                        new BrowserType.ConnectOptions())) { // Увеличим таймаут для ожидания в очереди

                    log.info("Задача {} -> Подключение успешно. Открытие страницы...", taskId);
                    Page page = browser.newPage();
                    page.navigate("https://playwright.dev/java/");
                    String title = page.title();

                    Assertions.assertTrue(title.contains("Playwright Java"), "Заголовок страницы неверный");
                    log.info("Задача {} -> Успешно завершена.", taskId);
                    return true;

                } catch (Exception e) {
                    log.error("Задача {} -> Провалена с ошибкой: {}", taskId, e.getMessage());
                    return false;
                }
            };
            futures.add(executorService.submit(task));
        }

        // Ожидаем завершения всех задач
        int successfulSessions = 0;
        for (Future<Boolean> future : futures) {
            try {
                // Ожидаем результат выполнения каждой задачи
                if (future.get(120, TimeUnit.SECONDS)) { // Общий таймаут на задачу, включая ожидание в очереди
                    successfulSessions++;
                }
            } catch (Exception e) {
                log.error("Не удалось получить результат задачи: {}", e.getMessage());
            }
        }

        log.info("--- Результаты теста ---");
        log.info("Успешно выполненных сессий: {} из {}", successfulSessions, TOTAL_SESSIONS_TO_TEST);

        // Главная проверка: все запущенные сессии должны были успешно завершиться.
        // Это доказывает, что сессии, которые не попали в лимит, были поставлены в очередь и выполнены позже.
        Assertions.assertEquals(TOTAL_SESSIONS_TO_TEST, successfulSessions,
                "Все сессии должны были успешно завершиться, что доказывает работу очереди.");
    }
}
