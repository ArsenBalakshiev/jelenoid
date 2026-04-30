package com.balakshievas.tests.selenium;

import org.junit.jupiter.api.AfterEach;
import org.openqa.selenium.NoAlertPresentException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.net.MalformedURLException;
import java.util.HashMap;
import java.net.URL;
import java.util.Map;

public class BaseSeleniumJelenoidTest {

    protected static final String HUB_URL = System.getProperty("hub.url",
            "http://localhost:4444/wd/hub");

    protected static final String TOKEN = System.getenv().getOrDefault("JELENOID_TOKEN",
            "super-secret-password");

    private final ThreadLocal<WebDriver> driverThreadLocal = new ThreadLocal<>();

    protected WebDriver createDriver(ChromeOptions specificOptions) throws MalformedURLException {
        ChromeOptions options = new ChromeOptions();
        options.setBrowserVersion("133");
        options.setCapability("unhandledPromptBehavior", "accept");

        Map<String, Object> selenoidOptions = new HashMap<>();
        selenoidOptions.put("enableVNC", true);
        //selenoidOptions.put("jelenoidToken", TOKEN);

        options.setCapability("selenoid:options", selenoidOptions);
        if (specificOptions != null) {
            options.merge(specificOptions);
        }

        WebDriver driver = new RemoteWebDriver(new URL(HUB_URL), options);
        driverThreadLocal.set(driver);
        return driver;
    }

    protected WebDriver createDriverWithOptions(ChromeOptions options) throws MalformedURLException {
        WebDriver driver = new RemoteWebDriver(new URL(HUB_URL), options);
        driverThreadLocal.set(driver);
        return driver;
    }

    protected WebDriver createDriver(ChromeOptions specificOptions, String browserVersion) throws MalformedURLException {
        ChromeOptions options = new ChromeOptions();
        options.setBrowserVersion(browserVersion);

        Map<String, Object> selenoidOptions = new HashMap<>();
        //selenoidOptions.put("jelenoidToken", TOKEN);

        options.setCapability("selenoid:options", selenoidOptions);
        if (specificOptions != null) {
            options.merge(specificOptions);
        }

        WebDriver driver = new RemoteWebDriver(new URL(HUB_URL), options);
        driverThreadLocal.set(driver);
        return driver;
    }

    protected WebDriver getDriver() {
        return driverThreadLocal.get();
    }

    @AfterEach
    public void tearDownDriver() {
        WebDriver driver = driverThreadLocal.get();
        if (driver != null) {
            try {
                try {
                    driver.switchTo().alert().dismiss();
                } catch (NoAlertPresentException ignored) {}
                driver.quit();
            } catch (Exception e) {
                System.err.println("Failed to quit driver: " + e.getMessage());
            } finally {
                driverThreadLocal.remove();
            }
        }
    }

}
