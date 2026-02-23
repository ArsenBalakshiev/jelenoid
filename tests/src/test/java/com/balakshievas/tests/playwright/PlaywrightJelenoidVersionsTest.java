package com.balakshievas.tests.playwright;

import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class PlaywrightJelenoidVersionsTest extends BasePlaywrightJelenoidTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "ws://localhost:4444/playwright",
            "ws://localhost:4444/playwright-1.53.0"
    })
    @DisplayName("Тестирование маршрутизации к разным версиям Playwright")
    void testDifferentPlaywrightVersions(String wsUrl) {
        Page page = createPage(wsUrl);

        page.navigate("http://host.docker.internal:8080/");

        Assertions.assertTrue(page.title().contains("Welcome to nginx!"),
                "Заголовок должен содержать 'Welcome to nginx!' при подключении к " + wsUrl);
    }
}
