package com.balakshievas.tests.selenide;

import com.codeborne.selenide.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.openqa.selenium.chrome.ChromeOptions;

import java.util.HashMap;
import java.util.Map;

public abstract class BaseSelenideTest {

    @BeforeAll
    public static void setupSelenide() {
        // 1. Указываем URL хаба Jelenoid
        Configuration.remote = System.getProperty("hub.url", "http://localhost:4444/wd/hub");
        Configuration.browser = "chrome";
        Configuration.browserVersion = "133";

        // Настройки для стабильности
        Configuration.pageLoadTimeout = 60000;
        Configuration.timeout = 10000; // Глобальное ожидание (Smart Waits)

        ChromeOptions options = new ChromeOptions();
        Map<String, Object> selenoidOptions = new HashMap<>();

        options.addArguments("--headless", "--no-sandbox", "--disable-dev-shm-usage");

        options.setCapability("selenoid:options", selenoidOptions);

        // 3. Подгружаем наши опции в Selenide
        Configuration.browserCapabilities = options;
    }
}
