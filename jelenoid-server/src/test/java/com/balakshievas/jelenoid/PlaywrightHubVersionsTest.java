package com.balakshievas.jelenoid;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PlaywrightHubVersionsTest {

    private Playwright playwright;
    private Browser browser;
    private Page page;

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
    public void test1_53_1() {
        playwright = Playwright.create();

        browser = playwright.chromium().connect("ws://localhost:4444/playwright-1.53.1");
        page = browser.newPage();

        page.navigate("http://host.docker.internal:8080/");

        // 2. Проверка заголовка
        String title = page.title();
        Assertions.assertTrue(title.contains("Welcome to nginx!"),
                "Заголовок должен содержать 'Welcome to nginx!'");
    }

    @Test
    public void test1_53_0() {
        playwright = Playwright.create();

        browser = playwright.chromium().connect("ws://localhost:4444/playwright-1.53.0");
        page = browser.newPage();

        page.navigate("http://host.docker.internal:8080/");

        // 2. Проверка заголовка
        String title = page.title();
        Assertions.assertTrue(title.contains("Welcome to nginx!"),
                "Заголовок должен содержать 'Welcome to nginx!'");
    }

    @Test
    public void testDef() {
        playwright = Playwright.create();

        browser = playwright.chromium().connect("ws://localhost:4444/playwright");
        page = browser.newPage();

        page.navigate("http://host.docker.internal:8080/");

        // 2. Проверка заголовка
        String title = page.title();
        Assertions.assertTrue(title.contains("Welcome to nginx!"),
                "Заголовок должен содержать 'Welcome to nginx!'");
    }
}
