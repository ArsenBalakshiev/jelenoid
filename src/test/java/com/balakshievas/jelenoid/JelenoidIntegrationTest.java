package com.balakshievas.jelenoid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    @DisplayName("Комплексное взаимодействие с формой")
    void shouldPerformComplexPageInteractions() throws MalformedURLException {
        URL hubUrl = new URL("http://localhost:8080/wd/hub");
        ChromeOptions chromeOptions = new ChromeOptions();
        chromeOptions.setCapability("browserName", "chrome");
        chromeOptions.setCapability("browserVersion", "133"); // Убедитесь, что версия верна

        WebDriver driver = null;
        try {
            driver = new RemoteWebDriver(hubUrl, chromeOptions);
            driver.manage().window().maximize();

            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));

            driver.get("http://webdriveruniversity.com/Contact-Us/contactus.html");

            WebElement firstNameField = wait.until(
                    ExpectedConditions.visibilityOfElementLocated(By.name("first_name"))
            );

            WebElement lastNameField = driver.findElement(By.xpath("//input[@name='last_name']"));
            List<WebElement> formInputs = driver.findElements(By.cssSelector("form#contact_form .feedback-input"));

            assertTrue(firstNameField.isDisplayed(), "Поле имени должно быть видимо");
            assertEquals(4, formInputs.size(), "На форме должно быть 4 поля ввода с классом 'feedback-input'");

            firstNameField.sendKeys("John");
            lastNameField.sendKeys("Doe");
            assertEquals("John", firstNameField.getAttribute("value"), "Атрибут value должен содержать введенный текст");

            WebElement resetButton = driver.findElement(By.cssSelector("input[type='reset']"));
            resetButton.click();
            assertEquals("", firstNameField.getAttribute("value"), "Поле должно быть пустым после сброса");

        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
    }
}
