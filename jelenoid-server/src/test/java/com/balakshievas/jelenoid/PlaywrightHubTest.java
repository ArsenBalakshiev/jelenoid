package com.balakshievas.jelenoid;

import com.microsoft.playwright.*;
import org.junit.jupiter.api.*;

public class PlaywrightHubTest {
    private Playwright playwright;
    private Browser browser;
    private Page page;

    @BeforeEach
    void setUp() {
        playwright = Playwright.create();

        browser = playwright.chromium().connect("ws://localhost:4444/playwright");
        page = browser.newPage();
    }

    @AfterEach
    void tearDown() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    @Test
    void testNavigationAndTitle() {
        // 1. Навигация на страницу
        page.navigate("http://host.docker.internal:8080/");

        // 2. Проверка заголовка
        String title = page.title();
        Assertions.assertTrue(title.contains("Welcome to nginx!"),
                "Заголовок должен содержать 'Welcome to nginx!'");
    }

    @Test
    void testNavigationAndTitle1() {
        // 1. Навигация на страницу
        page.navigate("http://host.docker.internal:8080/");

        // 2. Проверка заголовка
        String title = page.title();
        Assertions.assertTrue(title.contains("Welcome to nginx!"),
                "Заголовок должен содержать 'Welcome to nginx!'");
    }

    @Test
    void testNavigationAndTitle2() {
        // 1. Навигация на страницу
        page.navigate("http://host.docker.internal:8080/");

        // 2. Проверка заголовка
        String title = page.title();
        Assertions.assertTrue(title.contains("Welcome to nginx!"),
                "Заголовок должен содержать 'Welcome to nginx!'");
    }

    @Test
    void testNavigationAndTitle3() {
        // 1. Навигация на страницу
        page.navigate("http://host.docker.internal:8080/");

        // 2. Проверка заголовка
        String title = page.title();
        Assertions.assertTrue(title.contains("Welcome to nginx!"),
                "Заголовок должен содержать 'Welcome to nginx!'");
    }

    @Test
    void testNavigationAndTitle4() {
        // 1. Навигация на страницу
        page.navigate("http://host.docker.internal:8080/");

        // 2. Проверка заголовка
        String title = page.title();
        Assertions.assertTrue(title.contains("Welcome to nginx!"),
                "Заголовок должен содержать 'Welcome to nginx!'");
    }

    @Test
    void testNavigationAndTitle5() {
        // 1. Навигация на страницу
        page.navigate("http://host.docker.internal:8080/");

        // 2. Проверка заголовка
        String title = page.title();
        Assertions.assertTrue(title.contains("Welcome to nginx!"),
                "Заголовок должен содержать 'Welcome to nginx!'");
    }

    @Test
    void testNavigationAndTitle6() {
        // 1. Навигация на страницу
        page.navigate("http://host.docker.internal:8080/");

        // 2. Проверка заголовка
        String title = page.title();
        Assertions.assertTrue(title.contains("Welcome to nginx!"),
                "Заголовок должен содержать 'Welcome to nginx!'");
    }

    @Test
    void testNavigationAndTitle7() {
        // 1. Навигация на страницу
        page.navigate("http://host.docker.internal:8080/");

        // 2. Проверка заголовка
        String title = page.title();
        Assertions.assertTrue(title.contains("Welcome to nginx!"),
                "Заголовок должен содержать 'Welcome to nginx!'");
    }

    @Test
    void testNavigationAndTitle8() {
        // 1. Навигация на страницу
        page.navigate("http://host.docker.internal:8080/");

        // 2. Проверка заголовка
        String title = page.title();
        Assertions.assertTrue(title.contains("Welcome to nginx!"),
                "Заголовок должен содержать 'Welcome to nginx!'");
    }

    @Test
    void testScreenshotFunctionality() {
        page.navigate("http://host.docker.internal:8080/");

        // 3. Создание скриншота
        byte[] screenshot = page.screenshot();

        // Проверяем, что скриншот не пустой
        Assertions.assertTrue(screenshot.length > 0,
                "Скриншот должен быть не пустым");
    }

    @Test
    void testFormInteraction() {
        page.navigate("https://httpbin.org/forms/post");

        // 4. Заполнение формы
        page.locator("input[name='custname']").fill("John Doe");
        page.locator("input[name='custtel']").fill("+123456789");
        page.locator("input[value='small']").click();

        // 5. Проверка заполненных значений
        String nameValue = page.locator("input[name='custname']").inputValue();
        Assertions.assertEquals("John Doe", nameValue);
    }
}
