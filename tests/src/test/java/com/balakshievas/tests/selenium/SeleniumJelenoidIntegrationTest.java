package com.balakshievas.tests.selenium;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.HasDevTools;
import org.openqa.selenium.devtools.v133.network.Network;
import org.openqa.selenium.remote.Augmenter;
import org.openqa.selenium.remote.LocalFileDetector;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

class SeleniumJelenoidIntegrationTest extends BaseSeleniumJelenoidTest {

    @Test
    @DisplayName("Полный цикл: создание сессии, проксирование команды")
    void shouldCreateSessionAndProxyCommands() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get("https://www.google.com");
        Assertions.assertEquals("Google", driver.getTitle(), "Заголовок страницы должен быть 'Google'");
    }

    @Test
    @DisplayName("Воспроизведение ошибки 1009: слишком большое сообщение через DevTools")
    void shouldReproduceBufferLimitError() throws MalformedURLException {
        ChromeOptions chromeOptions = new ChromeOptions();
        chromeOptions.addArguments("--remote-allow-origins=*", "--no-sandbox");

        WebDriver driver = new Augmenter().augment(createDriver(chromeOptions));

        if (driver instanceof HasDevTools hasDevTools) {
            DevTools devTools = hasDevTools.getDevTools();
            devTools.createSession();

            // Включаем Runtime, чтобы ловить события консоли через WebSocket
            devTools.send(org.openqa.selenium.devtools.v133.runtime.Runtime.enable());

            // Выполняем JS-скрипт, который выведет строку размером 1 МБ в консоль.
            // Браузер попытается отправить эту огромную строку по CDP-сокету (DevTools).
            // Это должно переполнить стандартный 8KB буфер и уронить прокси-сокет.
            JavascriptExecutor js = (JavascriptExecutor) driver;
            js.executeScript("console.log('A'.repeat(1024 * 1024));");

            // Дадим немного времени сообщению дойти и уронить сокет
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Пытаемся сделать еще один вызов DevTools. 
            // Он должен упасть, если сокет был закрыт с ошибкой 1009.
            devTools.send(org.openqa.selenium.devtools.v133.runtime.Runtime.disable());
            
        } else {
            Assertions.fail("Драйвер не поддерживает DevTools. Проверьте Augmenter.");
        }
    }

    @Test
    @DisplayName("Комплексное взаимодействие с формой, скриншотами и DevTools")
    void shouldPerformComplexPageInteractions() throws MalformedURLException {
        ChromeOptions chromeOptions = new ChromeOptions();
        chromeOptions.addArguments("--remote-allow-origins=*", "--no-sandbox");

        WebDriver driver = new Augmenter().augment(createDriver(chromeOptions));
        driver.manage().window().maximize();

        if (driver instanceof HasDevTools hasDevTools) {
            DevTools devTools = hasDevTools.getDevTools();
            devTools.createSession();

            devTools.send(Network.emulateNetworkConditions(
                    true, -1, -1, -1, Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty()
            ));

            Assertions.assertThrows(WebDriverException.class, () -> driver.get("http://webdriveruniversity.com/"));

            devTools.send(Network.emulateNetworkConditions(
                    false, -1, -1, -1, Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty()
            ));
        } else {
            Assertions.fail("Драйвер не поддерживает DevTools. Проверьте Augmenter.");
        }

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
        driver.get("http://webdriveruniversity.com/Contact-Us/contactus.html");

        WebElement firstNameField = wait.until(ExpectedConditions.visibilityOfElementLocated(By.name("first_name")));
        firstNameField.sendKeys("John");

        if (driver instanceof TakesScreenshot takesScreenshot) {
            byte[] screenshotBytes = takesScreenshot.getScreenshotAs(OutputType.BYTES);
            Assertions.assertTrue(screenshotBytes.length > 0);
        }
    }

    @Test
    @DisplayName("Проверка VNC сессии")
    void myVncTest() throws MalformedURLException, InterruptedException {
        ChromeOptions options = new ChromeOptions();
        options.addArguments(
                "--remote-allow-origins=*",
                "--no-sandbox",
                "--window-size=1920,1080",
                "--disable-dev-shm-usage",
                "--disable-gpu"
        );

        Map<String, Object> selenoidOptions = new HashMap<>();
        selenoidOptions.put("enableVNC", true);
        options.setCapability("selenoid:options", selenoidOptions);

        WebDriver driver = createDriverWithOptions(options);
        driver.get("https://www.google.com");

        String sessionId = ((RemoteWebDriver) getDriver()).getSessionId().toString();
        System.out.println("VNC сессия активна. ID: " + sessionId);

        Thread.sleep(30000);
    }

    @Test
    @DisplayName("Успешная загрузка файла в удаленный контейнер Jelenoid")
    void shouldUploadFileToContainer() throws Exception {
        File tempFile = File.createTempFile("test-upload", ".txt");
        Files.writeString(tempFile.toPath(), "Hello from Jelenoid test!");
        tempFile.deleteOnExit();

        WebDriver driver = createDriver(new ChromeOptions());

        ((RemoteWebDriver) driver).setFileDetector(new LocalFileDetector());

        driver.get("https://the-internet.herokuapp.com/upload");

        WebElement fileInput = driver.findElement(By.id("file-upload"));
        fileInput.sendKeys(tempFile.getAbsolutePath());

        driver.findElement(By.id("file-submit")).click();

        WebElement uploadedFiles = driver.findElement(By.id("uploaded-files"));
        Assertions.assertTrue(
                uploadedFiles.getText().contains(tempFile.getName()),
                "На странице должно отображаться имя загруженного файла"
        );
    }

    @Test
    @DisplayName("Проверка работы с некорректной версией браузера")
    void testIncorrectVersion() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments(
                "--remote-allow-origins=*",
                "--no-sandbox",
                "--window-size=1920,1080",
                "--disable-dev-shm-usage",
                "--disable-gpu"
        );

        Map<String, Object> selenoidOptions = new HashMap<>();
        selenoidOptions.put("enableVNC", true);
        options.setCapability("selenoid:options", selenoidOptions);

        SessionNotCreatedException ex = Assertions.assertThrows(
                SessionNotCreatedException.class,
                () -> createDriver(options, "1333")
        );

        Assertions.assertTrue(
                ex.getMessage().contains("Not found browser version"),
                "Должно быть сообщение о ненайденной версии браузера"
        );
    }

}