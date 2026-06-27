package com.balakshievas.jelenoid.config;

import com.balakshievas.jelenoid.service.PlaywrightSessionService;
import com.balakshievas.jelenoid.utils.PlaywrightVersionInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private PlaywrightSessionService playwrightSessionService;

    private final PlaywrightVersionInterceptor playwrightVersionInterceptor = new PlaywrightVersionInterceptor();

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(
                        playwrightSessionService,
                        "/playwright",
                        "/playwright-{ver:[0-9\\.]+}"
                ).addInterceptors(playwrightVersionInterceptor)
                .setAllowedOrigins("*");
    }
}