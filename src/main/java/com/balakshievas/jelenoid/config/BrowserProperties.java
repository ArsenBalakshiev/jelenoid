package com.balakshievas.jelenoid.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "jelenoid.browsers")
public class BrowserProperties {

    private String configDir;

    // Getters and Setters
    public String getConfigDir() {
        return configDir;
    }

    public void setConfigDir(String configDir) {
        this.configDir = configDir;
    }
}
