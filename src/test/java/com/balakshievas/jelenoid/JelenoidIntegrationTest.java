package com.balakshievas.jelenoid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.HasDevTools;
import org.openqa.selenium.devtools.v133.network.Network;
import org.openqa.selenium.remote.Augmenter;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class JelenoidIntegrationTest {

    @Test
    @DisplayName("Полный цикл: создание сессии, проксирование команды и удаление сессии")
    void shouldCreateSessionAndProxyCommands() throws MalformedURLException {

        URL hubUrl = new URL("http://localhost:8080/wd/hub");

        ChromeOptions chromeOptions = new ChromeOptions();
        chromeOptions.setCapability("browserName", "chrome");
        chromeOptions.setCapability("browserVersion", "133");

        WebDriver driver = null;
        try {

            driver = new RemoteWebDriver(hubUrl, chromeOptions);

            driver.get("https://www.google.com");

            String pageTitle = driver.getTitle();

            assertEquals("Google", pageTitle, "Заголовок страницы должен быть 'Google'");

        } finally {
            if (driver != null) {
                driver.quit();
            }
        }

    }

    @Test
    @DisplayName("Комплексное взаимодействие с формой, скриншотами и DevTools")
    void shouldPerformComplexPageInteractions() throws MalformedURLException {
        URL hubUrl = new URL("http://localhost:8080/wd/hub");
        ChromeOptions chromeOptions = new ChromeOptions();
        chromeOptions.addArguments("--remote-allow-origins=*");
        chromeOptions.addArguments("--no-sandbox");

        DesiredCapabilities capabilities = new DesiredCapabilities();
        capabilities.setBrowserName("chrome");
        capabilities.setVersion("133"); // Убедитесь, что версия верна
        capabilities.setCapability(ChromeOptions.CAPABILITY, chromeOptions);

        WebDriver driver = null;
        try {
            RemoteWebDriver remoteDriver = new RemoteWebDriver(hubUrl, chromeOptions);

            driver = new Augmenter().augment(remoteDriver);

            driver.manage().window().maximize();

            // Теперь эта проверка будет работать!
            if (driver instanceof HasDevTools) {
                DevTools devTools = ((HasDevTools) driver).getDevTools();
                devTools.createSession();

                // ... остальной код для DevTools ...
                devTools.send(Network.emulateNetworkConditions(
                        true, -1, -1, -1, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()
                ));
                WebDriver finalDriver = driver;
                assertThrows(WebDriverException.class, () -> finalDriver.get("http://webdriveruniversity.com/"));
                devTools.send(Network.emulateNetworkConditions(
                        false, -1, -1, -1, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()
                ));
            } else {
                fail("Драйвер не поддерживает DevTools. Проверьте Augmenter.");
            }

            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
            driver.get("http://webdriveruniversity.com/Contact-Us/contactus.html");

            WebElement firstNameField = wait.until(ExpectedConditions.visibilityOfElementLocated(By.name("first_name")));
            firstNameField.sendKeys("John");

            if (driver instanceof TakesScreenshot) {
                byte[] screenshotBytes = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
                assertTrue(screenshotBytes.length > 0);
            }

        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
    }

    @Test
    void myVncTest() throws MalformedURLException {
        URL hubUrl = new URL("http://localhost:8080/wd/hub");

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--remote-allow-origins=*");
        options.addArguments("--no-sandbox");
        options.addArguments("--window-size=1920,1080");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-gpu");
        DesiredCapabilities capabilities = new DesiredCapabilities();
        capabilities.setBrowserName("chrome");
        capabilities.setVersion("133");
        capabilities.setCapability(ChromeOptions.CAPABILITY, options);
        Map<String, Object> selenoidOptions = new HashMap<>();
        selenoidOptions.put("enableVNC", true);

        capabilities.setCapability("selenoid:options", selenoidOptions);

        WebDriver driver = null;
        try {
            driver = new RemoteWebDriver(hubUrl, capabilities);

            driver.get("https://google.com");
            System.out.println("VNC сессия активна. ID: " + ((RemoteWebDriver) driver).getSessionId());
            Thread.sleep(30000);

        } catch (InterruptedException e) {
            // ...
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
    }
}
