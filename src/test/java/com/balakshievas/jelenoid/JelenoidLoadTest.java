package com.balakshievas.jelenoid;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.net.MalformedURLException;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Execution(ExecutionMode.CONCURRENT)
public class JelenoidLoadTest {

    private ThreadLocal<WebDriver> driver = new ThreadLocal<>();

    @BeforeEach
    public void setUp() throws MalformedURLException {
        System.out.printf("Thread %s: Starting new session...%n", Thread.currentThread().getName());

        URL hubUrl = new URL("http://localhost:4444/wd/hub");

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--headless");

        DesiredCapabilities capabilities = new DesiredCapabilities();
        capabilities.setBrowserName("chrome");
        capabilities.setVersion("133"); // Убедитесь, что версия верна
        capabilities.setCapability(ChromeOptions.CAPABILITY, options);

        RemoteWebDriver remoteDriver = new RemoteWebDriver(hubUrl, capabilities);
        driver.set(remoteDriver);
        System.out.printf("Thread %s: Session %s created.%n", Thread.currentThread().getName(), remoteDriver.getSessionId());
    }

    @RepeatedTest(25)
    public void loadTest(RepetitionInfo repetitionInfo) {
        String threadName = Thread.currentThread().getName();
        WebDriver currentDriver = driver.get();

        System.out.printf("Thread %s: Executing test repetition %d of %d...%n",
                threadName, repetitionInfo.getCurrentRepetition(), repetitionInfo.getTotalRepetitions());

        currentDriver.get("https://www.google.com");
        assertEquals("Google", currentDriver.getTitle());

        try {
            Thread.sleep(20000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.printf("Thread %s: Test repetition %d finished.%n",
                threadName, repetitionInfo.getCurrentRepetition());
    }

    @AfterEach
    public void tearDown() {
        String threadName = Thread.currentThread().getName();
        WebDriver currentDriver = driver.get();
        if (currentDriver != null) {
            System.out.printf("Thread %s: Closing session %s...%n",
                    threadName, ((RemoteWebDriver) currentDriver).getSessionId());
            currentDriver.quit();
        }
    }

}
