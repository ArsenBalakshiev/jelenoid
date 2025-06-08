package com.balakshievas.superselenoid;

import com.balakshievas.superselenoid.service.BrowserManagerService;
import com.balakshievas.superselenoid.service.ContainerManagerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Активируем поддержку Testcontainers для JUnit 5
@Testcontainers
// Запускаем полное Spring Boot приложение на случайном порту
//@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SuperSelenoidIntegrationTest {

    // Внедряем порт, на котором запустилось наше приложение
    @LocalServerPort
    private int port;

    @Autowired
    private ContainerManagerService containerManager;

    @Test
    @DisplayName("Полный цикл: создание сессии, проксирование команды и удаление сессии")
    void shouldCreateSessionAndProxyCommands() throws MalformedURLException {
        // --- 1. Подготовка ---
        // URL нашего хаба, запущенного локально для теста
        URL hubUrl = new URL("http://localhost:8080/wd/hub");

        // Запрашиваем браузер Chrome. Эти capabilities должны соответствовать вашему browsers.json
        ChromeOptions chromeOptions = new ChromeOptions();
        chromeOptions.setCapability("browserName", "chrome");
        chromeOptions.setCapability("browserVersion", "133");
        // Предположим, в browsers.json для chrome по умолчанию используется версия 114.0
        // chromeOptions.setCapability("browserVersion", "114.0");

        WebDriver driver = null;
        try {
            // --- 2. Создание сессии ---
            // Эта строка отправляет POST /session на наш хаб.
            // Приложение должно создать контейнер и вернуть sessionId.
            driver = new RemoteWebDriver(hubUrl, chromeOptions);

            // --- 3. Проксирование команды ---
            // Эта строка отправляет команду GET /session/{id}/url, которая должна быть спроксирована
            driver.get("https://www.google.com");

            // Эта команда (GET /session/{id}/title) также проксируется
            String pageTitle = driver.getTitle();

            // Проверяем, что команда была выполнена успешно
            assertEquals("Google", pageTitle, "Заголовок страницы должен быть 'Google'");

        } finally {
            // --- 4. Удаление сессии ---
            if (driver != null) {
                // Эта строка отправляет DELETE /session/{id}.
                // Наш хаб должен остановить и удалить контейнер.
                driver.quit();
            }
        }

    }

    @Test
    @DisplayName("Комплексное взаимодействие с формой")
    void shouldPerformComplexPageInteractions() throws MalformedURLException {
        URL hubUrl = new URL("http://localhost:" + port + "/wd/hub");
        ChromeOptions chromeOptions = new ChromeOptions();
        chromeOptions.setCapability("browserName", "chrome");
        chromeOptions.setCapability("browserVersion", "125.0"); // Убедитесь, что версия верна

        WebDriver driver = null;
        try {
            driver = new RemoteWebDriver(hubUrl, chromeOptions);
            driver.manage().window().maximize();

            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));

            driver.get("http://webdriveruniversity.com/Contact-Us/contactus.html");

            // --- НАЧАЛО ИСПРАВЛЕНИЯ ---

            // 1. Ждем, пока ключевой элемент с ПРАВИЛЬНЫМ классом станет видимым
            WebElement firstNameField = wait.until(
                    ExpectedConditions.visibilityOfElementLocated(By.name("first_name"))
            );

            // 2. Ищем остальные элементы, используя ПРАВИЛЬНЫЙ селектор
            WebElement lastNameField = driver.findElement(By.xpath("//input[@name='last_name']"));
            // Используем класс "feedback-input" вместо "form-control"
            List<WebElement> formInputs = driver.findElements(By.cssSelector("form#contact_form .feedback-input"));

            // 3. Проверяем корректность
            assertTrue(firstNameField.isDisplayed(), "Поле имени должно быть видимо");
            // Элементов по-прежнему 4 (3 инпута и 1 textarea)
            assertEquals(4, formInputs.size(), "На форме должно быть 4 поля ввода с классом 'feedback-input'");

            // --- КОНЕЦ ИСПРАВЛЕНИЯ ---

            // 4. Взаимодействие с элементами
            firstNameField.sendKeys("John");
            lastNameField.sendKeys("Doe");
            assertEquals("John", firstNameField.getAttribute("value"), "Атрибут value должен содержать введенный текст");

            // Ищем кнопки сброса и отправки
            WebElement resetButton = driver.findElement(By.cssSelector("input[type='reset']"));
            resetButton.click();
            assertEquals("", firstNameField.getAttribute("value"), "Поле должно быть пустым после сброса");

            // ... (остальная часть теста: JS, cookies, скриншоты - остается без изменений) ...

        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
    }
}
