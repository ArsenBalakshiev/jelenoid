package com.balakshievas.tests.selenium;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.HasDevTools;
import org.openqa.selenium.devtools.v133.network.Network;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.remote.Augmenter;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.FluentWait;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.net.MalformedURLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SeleniumE2ETest extends BaseSeleniumJelenoidTest {

    private static final String TEST_APP_BASE = "http://host.docker.internal:3000";

    @Test
    @DisplayName("E2E: Главная страница - базовая навигация")
    void shouldNavigateToHomePage() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/");
        
        try {
            driver.switchTo().alert().dismiss();
        } catch (NoAlertPresentException ignored) {}

        assertEquals("Test App", driver.getTitle());
        assertTrue(driver.getCurrentUrl().endsWith("/") || driver.getCurrentUrl().contains("test-app"));
    }

    @Test
    @DisplayName("E2E: Работа с несколькими окнами")
    void shouldHandleMultipleWindows() throws MalformedURLException, InterruptedException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/");
        
        try {
            driver.switchTo().alert().dismiss();
        } catch (NoAlertPresentException ignored) {}

        String originalWindow = driver.getWindowHandle();

        ((JavascriptExecutor) driver).executeScript("window.open('" + TEST_APP_BASE + "/slow.html')");

        Set<String> windows = driver.getWindowHandles();
        assertTrue(windows.size() >= 2);

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
        for (String window : windows) {
            if (!window.equals(originalWindow)) {
                driver.switchTo().window(window);
                wait.until(d -> d.getCurrentUrl().contains("slow.html"));
                break;
            }
        }

        assertTrue(driver.getCurrentUrl().contains("slow.html"));

        driver.switchTo().window(originalWindow);
        assertTrue(driver.getCurrentUrl().contains("test-app") || driver.getCurrentUrl().endsWith("/"));
    }

    @Test
    @DisplayName("E2E: Работа с cookies - установка и чтение")
    void shouldHandleCookies() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/cookies.html");

        Cookie cookie = new Cookie("testCookie", "testValue");
        driver.manage().addCookie(cookie);

        Cookie retrieved = driver.manage().getCookieNamed("testCookie");
        assertNotNull(retrieved);
        assertEquals("testCookie", retrieved.getName());
        assertEquals("testValue", retrieved.getValue());

        driver.manage().deleteCookie(retrieved);
        assertNull(driver.manage().getCookieNamed("testCookie"));
    }

    @Test
    @DisplayName("E2E: Выполнение JavaScript")
    void shouldExecuteJavaScript() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/");

        JavascriptExecutor js = (JavascriptExecutor) driver;

        String title = (String) js.executeScript("return document.title");
        assertEquals("Test App", title);

        Long height = (Long) js.executeScript("return document.body.scrollHeight");
        assertNotNull(height);
    }

    @Test
    @DisplayName("E2E: Работа с iframes")
    void shouldHandleIframes() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/iframe.html");

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
        wait.until(ExpectedConditions.frameToBeAvailableAndSwitchToIt(By.tagName("iframe")));

        WebElement body = driver.findElement(By.tagName("body"));
        assertNotNull(body.getText());

        driver.switchTo().defaultContent();
        assertNotNull(driver.getTitle());
    }

    @Test
    @DisplayName("E2E: Работа с dropdown (Select)")
    void shouldHandleDropdown() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/dropdown.html");

        Select select = new Select(driver.findElement(By.id("country")));
        assertFalse(select.isMultiple());

        select.selectByValue("us");
        assertEquals("us", select.getFirstSelectedOption().getAttribute("value"));

        select.selectByIndex(2);
        WebElement selected = select.getFirstSelectedOption();
        assertNotNull(selected);

        Select multiSelect = new Select(driver.findElement(By.id("multi")));
        assertTrue(multiSelect.isMultiple());
    }

    @Test
    @DisplayName("E2E: Явные ожидания (Explicit Waits) - динамический контент")
    void shouldHandleExplicitWaits() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/dynamic.html");

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));

        driver.findElement(By.id("loadBtn")).click();

        WebElement content = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("content")));
        assertTrue(content.getText().contains("Hello World"));
    }

    @Test
    @DisplayName("E2E: Slow page - ожидание загрузки")
    void shouldHandleSlowPage() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/slow.html");

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));

        driver.findElement(By.id("startBtn")).click();

        WebElement result = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("result")));
        assertTrue(result.getText().contains("Done"));
    }

    @Test
    @DisplayName("E2E: Keyboard events")
    void shouldHandleKeyboardEvents() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/forms.html");

        WebElement username = driver.findElement(By.name("username"));
        Actions actions = new Actions(driver);
        actions.click(username);
        actions.sendKeys("testuser").perform();
        assertEquals("testuser", username.getAttribute("value"));
    }

    @Test
    @DisplayName("E2E: Проверка window management")
    void shouldManageWindowSize() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/");

        driver.manage().window().setSize(new Dimension(800, 600));
        Dimension newSize = driver.manage().window().getSize();

        assertEquals(800, newSize.getWidth());
        assertEquals(600, newSize.getHeight());

        driver.manage().window().maximize();
        Dimension maximizedSize = driver.manage().window().getSize();
        assertTrue(maximizedSize.getWidth() > 800);
    }

    @Test
    @DisplayName("E2E: DevTools - Network emulation")
    void shouldUseDevToolsForNetworkEmulation() throws MalformedURLException {
        ChromeOptions chromeOptions = new ChromeOptions();
        chromeOptions.addArguments("--remote-allow-origins=*", "--no-sandbox");

        WebDriver driver = new Augmenter().augment(createDriver(chromeOptions));

        if (driver instanceof HasDevTools hasDevTools) {
            DevTools devTools = hasDevTools.getDevTools();
            devTools.createSession();

            devTools.send(Network.emulateNetworkConditions(
                    true, -1, -1, -1, Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty()
            ));

            assertThrows(WebDriverException.class, () -> driver.get(TEST_APP_BASE + "/"));

            devTools.send(Network.emulateNetworkConditions(
                    false, -1, -1, -1, Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty()
            ));

            driver.get(TEST_APP_BASE + "/");
            assertEquals("Test App", driver.getTitle());
        }
    }

    @Test
    @DisplayName("E2E: DevTools - JavaScript execution via DevTools")
    void shouldExecuteJavaScriptViaDevTools() throws MalformedURLException {
        ChromeOptions chromeOptions = new ChromeOptions();
        chromeOptions.addArguments("--remote-allow-origins=*", "--no-sandbox");

        WebDriver driver = new Augmenter().augment(createDriver(chromeOptions));

        if (driver instanceof HasDevTools hasDevTools) {
            DevTools devTools = hasDevTools.getDevTools();
            devTools.createSession();
            devTools.send(org.openqa.selenium.devtools.v133.runtime.Runtime.enable());

            driver.get(TEST_APP_BASE + "/");

            JavascriptExecutor js = (JavascriptExecutor) driver;
            String title = (String) js.executeScript("return document.title");
            assertEquals("Test App", title);
        }
    }

    @Test
    @DisplayName("E2E: Implicit waits")
    void shouldHandleImplicitWaits() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));

        driver.get(TEST_APP_BASE + "/dynamic.html");
        driver.findElement(By.id("loadBtn")).click();

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
        WebElement content = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("content")));
        assertTrue(content.getText().contains("Hello World"));

        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(0));
    }

    @Test
    @DisplayName("E2E: Page refresh и навигация")
    void shouldHandlePageRefreshAndNavigation() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/");
        
        try {
            driver.switchTo().alert().dismiss();
        } catch (NoAlertPresentException ignored) {}

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
        
        driver.navigate().to(TEST_APP_BASE + "/slow.html");
        wait.until(d -> d.getCurrentUrl().contains("slow.html"));
        assertTrue(driver.getCurrentUrl().contains("slow.html"));

        driver.navigate().back();
        wait.until(d -> d.getCurrentUrl().contains("test-app") || d.getCurrentUrl().endsWith("/"));
        assertTrue(driver.getCurrentUrl().contains("test-app") || driver.getCurrentUrl().endsWith("/"));

        driver.navigate().forward();
        wait.until(d -> d.getCurrentUrl().contains("slow.html"));
        assertTrue(driver.getCurrentUrl().contains("slow.html"));

        driver.navigate().refresh();
        assertTrue(driver.getCurrentUrl().contains("slow.html"));
    }

    @Test
    @DisplayName("E2E: Find elements by various selectors")
    void shouldFindElementsByVariousSelectors() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/forms.html");

        assertNotNull(driver.findElement(By.id("loginForm")));
        assertNotNull(driver.findElement(By.name("username")));
        assertNotNull(driver.findElement(By.cssSelector("input[type='text']")));
        assertNotNull(driver.findElement(By.xpath("//input[@type='password']")));
    }

    @Test
    @DisplayName("E2E: Работа с Alert/Confirm/Prompt")
    void shouldHandleAlerts() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/alerts.html");

        driver.findElement(By.id("alertBtn")).click();

        Alert alert = driver.switchTo().alert();
        assertNotNull(alert.getText());

        alert.dismiss();
    }

    @Test
    @DisplayName("E2E: Скриншоты разных типов")
    void shouldTakeScreenshots() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/");

        if (driver instanceof TakesScreenshot screenshotDriver) {
            byte[] fullPageScreenshot = screenshotDriver.getScreenshotAs(OutputType.BYTES);
            assertTrue(fullPageScreenshot.length > 0);

            WebElement element = driver.findElement(By.tagName("h1"));
            byte[] elementScreenshot = element.getScreenshotAs(OutputType.BYTES);
            assertTrue(elementScreenshot.length > 0);
        }
    }

    @Test
    @DisplayName("E2E: Работа с таблицами")
    void shouldHandleTables() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/tables.html");

        List<WebElement> rows = driver.findElements(By.cssSelector("table tbody tr"));
        assertTrue(rows.size() >= 5);

        WebElement firstRow = rows.get(0);
        assertNotNull(firstRow.getText());
    }

    @Test
    @DisplayName("E2E: Click methods comparison")
    void shouldHandleDifferentClickMethods() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/");

        WebElement button = driver.findElement(By.id("mainBtn"));

        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", button);

        assertNotNull(driver.getCurrentUrl());
    }

    @Test
    @DisplayName("E2E: Fluent wait")
    void shouldHandleFluentWait() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/dynamic.html");

        FluentWait<WebDriver> fluentWait = new FluentWait<>(driver)
                .withTimeout(Duration.ofSeconds(10))
                .pollingEvery(Duration.ofMillis(500))
                .ignoring(org.openqa.selenium.NoSuchElementException.class);

        driver.findElement(By.id("loadBtn")).click();

        WebElement content = fluentWait.until(d -> {
            WebElement el = d.findElement(By.id("content"));
            if (el.getText().contains("Hello World")) {
                return el;
            }
            return null;
        });
        assertTrue(content.getText().contains("Hello World"));
    }

    @Test
    @DisplayName("E2E: Работа с hidden elements")
    void shouldHandleHiddenElements() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/hidden.html");

        WebElement hiddenElement = driver.findElement(By.id("hiddenElement"));
        assertNotNull(hiddenElement);

        driver.findElement(By.id("showBtn")).click();

        WebElement shownElement = driver.findElement(By.id("hiddenElement"));
        assertTrue("block".equals(shownElement.getCssValue("display")));
    }

    @Test
    @DisplayName("E2E: Page source verification")
    void shouldVerifyPageSource() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/");

        String pageSource = driver.getPageSource();
        assertNotNull(pageSource);
        assertTrue(pageSource.contains("html"));
    }

    @Test
    @DisplayName("E2E: Find multiple elements")
    void shouldFindMultipleElements() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/");

        List<WebElement> links = driver.findElements(By.tagName("a"));
        assertTrue(links.size() > 0);

        for (WebElement link : links) {
            assertNotNull(link.getText());
        }
    }

    @Test
    @DisplayName("E2E: Element state verification - checkboxes")
    void shouldVerifyCheckboxState() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/checkboxes.html");

        WebElement checkbox = driver.findElement(By.xpath("(//input[@type='checkbox'])[1]"));

        assertFalse(checkbox.isSelected());

        checkbox.click();

        assertTrue(checkbox.isSelected());
    }

    @Test
    @DisplayName("E2E: Enabled/Disabled element verification")
    void shouldVerifyElementEnabledState() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/forms.html");

        WebElement enabledInput = driver.findElement(By.name("username"));
        assertTrue(enabledInput.isEnabled());
    }

    @Test
    @DisplayName("E2E: Radio button handling")
    void shouldHandleRadioButtons() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/checkboxes.html");

        List<WebElement> radioButtons = driver.findElements(By.name("size"));

        for (WebElement radio : radioButtons) {
            if (!radio.isSelected()) {
                radio.click();
                assertTrue(radio.isSelected());
            }
        }
    }

    @Test
    @DisplayName("E2E: CSS value verification")
    void shouldVerifyCssValues() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/");

        WebElement h1 = driver.findElement(By.tagName("h1"));

        String fontSize = h1.getCssValue("font-size");
        assertNotNull(fontSize);
    }

    @Test
    @DisplayName("E2E: Tag name verification")
    void shouldVerifyTagName() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/");

        WebElement h1 = driver.findElement(By.tagName("h1"));
        assertEquals("h1", h1.getTagName());
    }

    @Test
    @DisplayName("E2E: Clear input field")
    void shouldClearInputField() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/cookies.html");

        WebElement input = driver.findElement(By.id("cookieValue"));
        input.sendKeys("test");
        assertEquals("test", input.getAttribute("value"));

        input.clear();
        assertEquals("", input.getAttribute("value"));
    }

    @Test
    @DisplayName("E2E: Submit form")
    void shouldSubmitForm() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/forms.html");

        driver.findElement(By.name("username")).sendKeys("testuser");
        driver.findElement(By.name("password")).sendKeys("testpass");
        driver.findElement(By.name("password")).submit();
    }

    @Test
    @DisplayName("E2E: Drag and Drop")
    void shouldHandleDragAndDrop() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/dragdrop.html");

        WebElement draggable = driver.findElement(By.id("draggable"));
        WebElement target = driver.findElement(By.id("target"));

        Actions actions = new Actions(driver);
        actions.dragAndDrop(draggable, target).perform();

        assertNotNull(target.getText());
    }

    @Test
    @DisplayName("E2E: File upload")
    void shouldHandleFileUpload() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/upload.html");

        WebElement fileInput = driver.findElement(By.id("fileInput"));
        assertNotNull(fileInput);
    }

    @Test
    @DisplayName("E2E: LocalStorage")
    void shouldHandleLocalStorage() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        driver.get(TEST_APP_BASE + "/storage.html");

        driver.findElement(By.id("setBtn")).click();

        JavascriptExecutor js = (JavascriptExecutor) driver;
        String value = (String) js.executeScript("return localStorage.getItem('testKey')");
        assertEquals("testValue", value);
    }

    @Test
    @DisplayName("E2E: Создание сессии с конкретной версией Chrome")
    void shouldCreateSessionWithSpecificBrowserVersion() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions(), "133");

        driver.get(TEST_APP_BASE + "/");
        assertEquals("Test App", driver.getTitle());
    }

    @Test
    @DisplayName("E2E: Navigation to all test-app pages")
    void shouldNavigateToAllPages() throws MalformedURLException {
        WebDriver driver = createDriver(new ChromeOptions());
        String[] pages = {"index.html", "slow.html", "dynamic.html", "cookies.html",
                "iframe.html", "dropdown.html", "checkboxes.html", "alerts.html",
                "forms.html", "hidden.html", "dragdrop.html", "upload.html",
                "tables.html", "storage.html"};

        for (String page : pages) {
            driver.get(TEST_APP_BASE + "/" + page);
            assertNotNull(driver.getTitle());
        }
    }
}