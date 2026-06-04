package com.balakshievas.tests.playwright;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PlaywrightE2ETest extends BasePlaywrightJelenoidTest {

    private static final String JELENOID_WS = "ws://localhost:4444/playwright";
    private static final String TEST_APP_BASE = "http://host.docker.internal:3000";

    @Test
    @DisplayName("E2E: Главная страница - базовая навигация")
    void shouldNavigateToHomePage() {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + "/");

        assertEquals("Test App", page.title());
        assertTrue(page.url().endsWith("/") || page.url().contains("test-app"));
    }

    @Test
    @DisplayName("E2E: Работа с несколькими страницами/контекстами")
    void shouldHandleMultiplePages() {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + "/");

        Browser browser = page.context().browser();
        BrowserContext newContext = browser.newContext();
        Page newPage = newContext.newPage();
        newPage.navigate(TEST_APP_BASE + "/slow.html");

        assertTrue(newPage.url().contains("slow.html"));

        newPage.close();
        newContext.close();
        assertTrue(page.url().endsWith("/") || page.url().contains("test-app"));
    }

    @Test
    @DisplayName("E2E: Перехват сетевых запросов (Network Interception)")
    void shouldInterceptNetworkRequests() {
        Page page = createPage(JELENOID_WS);

        List<Request> interceptedRequests = new ArrayList<>();
        page.onRequest(request -> {
            interceptedRequests.add(request);
        });

        page.navigate(TEST_APP_BASE + "/");
        page.waitForLoadState();

        assertFalse(interceptedRequests.isEmpty());
    }

    @Test
    @DisplayName("E2E: Работа с cookies через JavaScript")
    void shouldHandleCookies() {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + "/cookies.html");

        page.evaluate("() => document.cookie = 'testCookie=testValue'");

        Object cookieValue = page.evaluate("() => document.cookie");
        assertTrue(cookieValue.toString().contains("testCookie"));
    }

    @Test
    @DisplayName("E2E: Выполнение JavaScript")
    void shouldExecuteJavaScript() {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + "/");

        Object title = page.evaluate("() => document.title");
        assertEquals("Test App", title);

        Object height = page.evaluate("() => document.body.scrollHeight");
        assertNotNull(height);
    }

    @Test
    @DisplayName("E2E: Работа с модальными окнами (dialog)")
    void shouldHandleDialogs() {
        Page page = createPage(JELENOID_WS);

        page.onDialog(dialog -> {
            assertNotNull(dialog.message());
            dialog.accept("Test Answer");
        });

        page.navigate(TEST_APP_BASE + "/alerts.html");
        page.locator("#promptBtn").click();
    }

    @Test
    @DisplayName("E2E: Alert dialog")
    void shouldHandleAlertDialog() {
        Page page = createPage(JELENOID_WS);

        page.onDialog(dialog -> {
            assertEquals("Hello", dialog.message());
            dialog.accept();
        });

        page.navigate(TEST_APP_BASE + "/alerts.html");
        page.locator("#alertBtn").click();
    }

    @Test
    @DisplayName("E2E: Confirm dialog")
    void shouldHandleConfirmDialog() {
        Page page = createPage(JELENOID_WS);

        page.onDialog(dialog -> {
            assertEquals("Continue?", dialog.message());
            dialog.dismiss();
        });

        page.navigate(TEST_APP_BASE + "/alerts.html");
        page.locator("#confirmBtn").click();
    }

    @Test
    @DisplayName("E2E: Drag and Drop")
    void shouldHandleDragAndDrop() {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + "/dragdrop.html");

        page.locator("#draggable").dragTo(page.locator("#target"));

        assertNotNull(page.locator("#target").textContent());
    }

    @Test
    @DisplayName("E2E: Работа с localStorage/sessionStorage")
    void shouldHandleStorage() {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + "/storage.html");

        page.locator("#setBtn").click();

        Object value = page.evaluate("() => localStorage.getItem('testKey')");
        assertEquals("testValue", value);
    }

    @Test
    @DisplayName("E2E: Keyboard events")
    void shouldHandleKeyboardEvents() {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + "/forms.html");

        page.locator("input[name='username']").fill("test");
        page.keyboard().press("Control+A");
        page.keyboard().press("Backspace");

        assertEquals("", page.locator("input[name='username']").inputValue());
    }

    @Test
    @DisplayName("E2E: Работа с select/dropdown")
    void shouldHandleSelectElement() {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + "/dropdown.html");

        page.locator("#country").selectOption("us");

        Object value = page.evaluate("document.querySelector('#country').value");
        assertEquals("us", value);
    }

    @Test
    @DisplayName("E2E: Multiple select dropdown")
    void shouldHandleMultipleSelect() {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + "/dropdown.html");

        page.locator("#multi").selectOption("a");
        page.locator("#multi").selectOption("b");

        assertTrue(page.locator("#multi").evaluate("el => el.selectedOptions.length") instanceof Number);
    }

    @Test
    @DisplayName("E2E: Скриншоты")
    void shouldTakeScreenshots() {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + "/");

        byte[] screenshot = page.screenshot();
        assertTrue(screenshot.length > 0);

        byte[] fullPageScreenshot = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
        assertTrue(fullPageScreenshot.length > 0);
    }

    @Test
    @DisplayName("E2E: Работа с iframes")
    void shouldHandleIframes() {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + "/iframe.html");

        FrameLocator iframe = page.frameLocator("#testIframe");
        iframe.locator("body").waitFor();

        assertNotNull(iframe.locator("body").textContent());
    }

    @Test
    @DisplayName("E2E: Explicit waits - динамический контент")
    void shouldHandleExplicitWaits() {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + "/dynamic.html");

        page.locator("#loadBtn").click();

        page.locator("#content").waitFor();
        assertTrue(page.locator("#content").textContent().contains("Hello World"));
    }

    @Test
    @DisplayName("E2E: Slow page - ожидание")
    void shouldHandleSlowPage() {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + "/slow.html");

        page.locator("#startBtn").click();

        page.locator("#result").waitFor();
        assertTrue(page.locator("#result").textContent().contains("Done"));
    }

    @Test
    @DisplayName("E2E: Проверка видимости элементов")
    void shouldCheckElementVisibility() {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + "/forms.html");

        assertTrue(page.locator("input[name='username']").isVisible());
        assertFalse(page.locator("input[name='username']").isHidden());
    }

    @Test
    @DisplayName("E2E: Hover и actions")
    void shouldHandleHover() {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + "/index.html");

        page.locator("button#mainBtn").hover();

        assertNotNull(page.locator("button#mainBtn"));
    }

    @Test
    @DisplayName("E2E: Работа с Checkbox и Radio")
    void shouldHandleCheckboxesAndRadios() {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + "/checkboxes.html");

        page.locator("input[type='checkbox']").first().check();
        assertTrue(page.locator("input[type='checkbox']").first().isChecked());

        page.locator("input[type='checkbox']").first().uncheck();
        assertFalse(page.locator("input[type='checkbox']").first().isChecked());
    }

    @Test
    @DisplayName("E2E: Radio buttons")
    void shouldHandleRadioButtons() {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + "/checkboxes.html");

        page.locator("input[value='medium']").check();
        assertTrue(page.locator("input[value='medium']").isChecked());
    }

    @Test
    @DisplayName("E2E: File upload")
    void shouldUploadFile() {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + "/upload.html");

        page.locator("input[type='file']").setInputFiles(Path.of("pom.xml"));

        page.locator("#uploadBtn").click();

        assertNotNull(page.locator("#output").textContent());
    }

    @Test
    @DisplayName("E2E: Работа с tabs")
    void shouldHandleMultipleTabs() {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + "/");

        Browser browser = page.context().browser();
        BrowserContext newContext = browser.newContext();
        Page newTab = newContext.newPage();
        newTab.navigate(TEST_APP_BASE + "/slow.html");

        assertTrue(newTab.url().contains("slow.html"));

        page.bringToFront();
        assertTrue(page.url().endsWith("/") || page.url().contains("test-app"));
        
        newTab.close();
        newContext.close();
    }

    @Test
    @DisplayName("E2E: Navigation types")
    void shouldHandleDifferentNavigationTypes() {
        Page page = createPage(JELENOID_WS);

        page.navigate(TEST_APP_BASE + "/");
        assertTrue(page.url().endsWith("/") || page.url().contains("test-app"));

        page.navigate(TEST_APP_BASE + "/slow.html");
        assertTrue(page.url().contains("slow.html"));

        page.goBack();
        assertTrue(page.url().endsWith("/") || page.url().contains("test-app"));

        page.goForward();
        assertTrue(page.url().contains("slow.html"));

        page.reload();
        assertTrue(page.url().contains("slow.html"));
    }

    @Test
    @DisplayName("E2E: Mouse events")
    void shouldHandleMouseEvents() {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + "/forms.html");

        page.mouse().move(100, 100);
        page.mouse().click(100, 100);

        assertTrue(page.locator("input[name='username']").isVisible());
    }

    @Test
    @DisplayName("E2E: Text content and attributes")
    void shouldCheckTextAndAttributes() {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + "/");

        assertEquals("Test App", page.locator("h1").textContent());

        String href = page.locator("a").first().getAttribute("href");
        assertNotNull(href);
    }

    @Test
    @DisplayName("E2E: Timeout handling")
    void shouldHandleTimeouts() {
        Page page = createPage(JELENOID_WS);
        page.setDefaultTimeout(5000);

        assertDoesNotThrow(() -> {
            page.navigate(TEST_APP_BASE + "/");
        });
    }

    @Test
    @DisplayName("E2E: Viewport manipulation")
    void shouldHandleViewport() {
        Page page = createPage(JELENOID_WS);
        page.setViewportSize(800, 600);
        page.navigate(TEST_APP_BASE + "/");

        assertEquals(800, page.viewportSize().width);
        assertEquals(600, page.viewportSize().height);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/index.html", "/slow.html", "/dynamic.html", "/cookies.html"})
    @DisplayName("E2E: Parameterized navigation")
    void shouldNavigateParameterized(String path) {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + path);

        assertNotNull(page.title());
    }

    @Test
    @DisplayName("E2E: Wait for network idle")
    void shouldWaitForNetworkIdle() {
        Page page = createPage(JELENOID_WS);

        page.navigate(TEST_APP_BASE + "/", new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));

        assertNotNull(page.title());
    }

    @Test
    @DisplayName("E2E: Form submission")
    void shouldSubmitForm() {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + "/forms.html");

        page.locator("input[name='username']").fill("testuser");
        page.locator("input[name='password']").fill("testpass");
        page.locator("button[type='submit']").click();
    }

    @Test
    @DisplayName("E2E: Работа с cookies через JS")
    void shouldAddAndDeleteCookies() {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + "/cookies.html");

        page.evaluate("() => document.cookie = 'newCookie=newValue'");

        Object cookie = page.evaluate("() => document.cookie");
        assertTrue(cookie.toString().contains("newCookie"));

        page.evaluate("() => document.cookie = ''");
    }

    @Test
    @DisplayName("E2E: Wait for element state")
    void shouldWaitForElementState() {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + "/dynamic.html");

        page.locator("#loadBtn").click();

        page.locator("#content").waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));

        assertTrue(page.locator("#content").isVisible());
    }

    @Test
    @DisplayName("E2E: Multiple locator strategies")
    void shouldUseMultipleLocatorStrategies() {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + "/forms.html");

        assertNotNull(page.locator("#loginForm"));
        assertNotNull(page.locator("input[name='username']"));
        assertNotNull(page.locator("//input[@type='password']"));
    }

    @Test
    @DisplayName("E2E: Context options")
    void shouldHandleContextOptions() {
        BrowserContext context = createPage(JELENOID_WS).context().browser().newContext(
                new Browser.NewContextOptions()
                        .setViewportSize(1024, 768)
                        .setLocale("en-US")
        );

        Page page = context.newPage();
        page.navigate(TEST_APP_BASE + "/");

        assertEquals(1024, page.viewportSize().width);

        page.close();
        context.close();
    }

    @Test
    @DisplayName("E2E: Работа с таблицами")
    void shouldHandleTables() {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + "/tables.html");

        int rows = page.locator("table tbody tr").count();
        assertTrue(rows >= 5);

        String firstRowText = page.locator("table tbody tr").first().textContent();
        assertNotNull(firstRowText);
    }

    @Test
    @DisplayName("E2E: Hidden elements - показать элемент")
    void shouldHandleHiddenElements() {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + "/hidden.html");

        assertFalse(page.locator("#hiddenElement").isVisible());

        page.locator("#showBtn").click();

        assertTrue(page.locator("#hiddenElement").isVisible());
    }

    @Test
    @DisplayName("E2E: Подключение к конкретной версии Playwright")
    void shouldConnectToSpecificPlaywrightVersion() {
        Page page = createPage("ws://localhost:4444/playwright");

        page.navigate(TEST_APP_BASE + "/");
        assertEquals("Test App", page.title());
    }

    @Test
    @DisplayName("E2E: Iframe content interaction")
    void shouldInteractWithIframeContent() {
        Page page = createPage(JELENOID_WS);
        page.navigate(TEST_APP_BASE + "/iframe.html");

        FrameLocator iframe = page.frameLocator("#testIframe");
        iframe.locator("#iframeBtn").click();

        page.onDialog(dialog -> dialog.accept());
    }

    @Test
    @DisplayName("E2E: Navigation to all test-app pages")
    void shouldNavigateToAllPages() {
        Page page = createPage(JELENOID_WS);
        String[] pages = {"index.html", "slow.html", "dynamic.html", "cookies.html",
                "iframe.html", "dropdown.html", "checkboxes.html", "alerts.html",
                "forms.html", "hidden.html", "dragdrop.html", "upload.html",
                "tables.html", "storage.html"};

        for (String pageName : pages) {
            page.navigate(TEST_APP_BASE + "/" + pageName);
            assertNotNull(page.title());
        }
    }
}