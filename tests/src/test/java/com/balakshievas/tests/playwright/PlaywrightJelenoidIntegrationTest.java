package com.balakshievas.tests.playwright;

import com.microsoft.playwright.Page;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class PlaywrightJelenoidIntegrationTest extends BasePlaywrightJelenoidTest {

    @BeforeEach
    void setUp() {
        createPage();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("Проверка навигации и заголовка (Многократный прогон)")
    void testNavigationAndTitle(int iteration) {
        Page page = getPage();
        page.navigate("http://host.docker.internal:8080/");

        Assertions.assertTrue(page.title().contains("Welcome to nginx!"),
                "Заголовок должен содержать 'Welcome to nginx!'");
    }

    @Test
    @DisplayName("Проверка функционала создания скриншотов")
    void testScreenshotFunctionality() {
        Page page = getPage();
        page.navigate("http://host.docker.internal:8080/");

        byte[] screenshot = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
        Assertions.assertTrue(screenshot.length > 0, "Скриншот не должен быть пустым");
    }

    @Test
    @DisplayName("Проверка взаимодействия с формой")
    void testFormInteraction() {
        Page page = getPage();
        page.navigate("https://httpbin.org/forms/post");

        page.locator("input[name='custname']").fill("John Doe");
        page.locator("input[name='custtel']").fill("+123456789");
        page.locator("input[value='small']").click();

        Assertions.assertEquals("John Doe", page.locator("input[name='custname']").inputValue());
    }
}
