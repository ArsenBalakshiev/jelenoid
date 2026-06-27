package com.balakshievas.jelenoid.exception;

public class BrowserVersionNotFoundException extends RuntimeException {
    private final String browserName;
    private final String browserVersion;

    public BrowserVersionNotFoundException(String browserName, String browserVersion) {
        super("Not found browser version: %s:%s".formatted(browserName, browserVersion));
        this.browserName = browserName;
        this.browserVersion = browserVersion;
    }

    public String getBrowserName() { return browserName; }
    public String getBrowserVersion() { return browserVersion; }
}
