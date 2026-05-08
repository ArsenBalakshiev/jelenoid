package com.balakshievas.jelenoid.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "jelenoid.browsers")
public class BrowserProperties {

    private String configDir;

}
