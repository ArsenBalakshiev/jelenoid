package com.balakshievas.tests.playwright;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterEach;

public abstract class BasePlaywrightJelenoidTest {

    protected static final String DEFAULT_WS_URL = System.getProperty("playwright.ws",
            "ws://localhost:4444/playwright");

    private final ThreadLocal<Playwright> playwrightThreadLocal = new ThreadLocal<>();
    private final ThreadLocal<Browser> browserThreadLocal = new ThreadLocal<>();
    private final ThreadLocal<Page> pageThreadLocal = new ThreadLocal<>();

    protected Page createPage(String wsUrl) {
        Playwright playwright = Playwright.create();
        playwrightThreadLocal.set(playwright);

        Browser browser = playwright.chromium().connect(wsUrl);
        browserThreadLocal.set(browser);

        Page page = browser.newPage();
        pageThreadLocal.set(page);

        return page;
    }

    protected Page createPage() {
        return createPage(DEFAULT_WS_URL);
    }

    protected Page getPage() {
        return pageThreadLocal.get();
    }

    @AfterEach
    public void tearDown() {
        Page page = pageThreadLocal.get();
        if (page != null) page.close();

        Browser browser = browserThreadLocal.get();
        if (browser != null) browser.close();

        Playwright playwright = playwrightThreadLocal.get();
        if (playwright != null) playwright.close();

        pageThreadLocal.remove();
        browserThreadLocal.remove();
        playwrightThreadLocal.remove();
    }
}
