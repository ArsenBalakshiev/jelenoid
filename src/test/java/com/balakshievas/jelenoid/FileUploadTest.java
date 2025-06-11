package com.balakshievas.jelenoid;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.LocalFileDetector;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class FileUploadTest {

    @Test
    void shouldUploadFileToContainer() throws Exception {
        // Создаем временный файл для теста
        File tempFile = File.createTempFile("test-upload", ".txt");
        Files.writeString(tempFile.toPath(), "Hello from Jelenoid test!");
        tempFile.deleteOnExit();

        URL hubUrl = new URL("http://localhost:4444/wd/hub");
        RemoteWebDriver driver = new RemoteWebDriver(hubUrl, new ChromeOptions());

        // ВАЖНО: Устанавливаем FileDetector, чтобы Selenium знал, что нужно загружать файлы
        driver.setFileDetector(new LocalFileDetector());

        try {
            driver.get("https://the-internet.herokuapp.com/upload");

            WebElement fileInput = driver.findElement(By.id("file-upload"));

            // Отправляем путь к локальному файлу. Selenium сам обработает его
            // и отправит на ваш новый эндпоинт /session/{sessionId}/file
            fileInput.sendKeys(tempFile.getAbsolutePath());

            driver.findElement(By.id("file-submit")).click();

            WebElement uploadedFiles = driver.findElement(By.id("uploaded-files"));
            // Проверяем, что имя файла отображается на странице
            assertTrue(uploadedFiles.getText().contains(tempFile.getName()));

        } finally {
            driver.quit();
        }
    }
}