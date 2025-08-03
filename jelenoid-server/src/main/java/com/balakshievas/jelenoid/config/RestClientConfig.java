package com.balakshievas.jelenoid.config;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.TimeValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    private static final Logger log = LoggerFactory.getLogger(RestClientConfig.class);

    @Bean
    public RestClient restClient() {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(100); // Максимальное число соединений в пуле
        connectionManager.setDefaultMaxPerRoute(20); // Максимальное число соединений к одному хосту

        connectionManager.setValidateAfterInactivity(TimeValue.ofSeconds(5));

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .disableAutomaticRetries()
                .build();

        var requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
        requestFactory.setConnectionRequestTimeout(Duration.ofSeconds(5)); // Таймаут на получение соединения из пула
        requestFactory.setConnectTimeout(Duration.ofSeconds(5)); // Таймаут на установку TCP-соединения

        ClientHttpRequestInterceptor loggingInterceptor = (request, body, execution) -> {
            log.info("Request URI: {}", request.getURI());
            return execution.execute(request, body);
        };

        return RestClient.builder()
                .requestFactory(requestFactory)
                .requestInterceptor(loggingInterceptor)
                .build();
    }
}
