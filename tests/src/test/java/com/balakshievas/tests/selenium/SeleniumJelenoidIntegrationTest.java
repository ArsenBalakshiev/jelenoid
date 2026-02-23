package com.balakshievas.tests.selenium;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
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

        WebDriver driver = createDriver(options);
        driver.get("https://google.com");

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
}
