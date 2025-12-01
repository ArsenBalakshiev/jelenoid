package com.balakshievas.jelenoid.config;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig; // Добавлено для Read Timeout
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.concurrent.TimeUnit;

@Configuration
public class RestClientConfig {

    private static final Logger log = LoggerFactory.getLogger(RestClientConfig.class);

    @Bean
    public RestClient restClient() {
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(5, TimeUnit.SECONDS))
                .setSocketTimeout(Timeout.of(300, TimeUnit.SECONDS))
                .build();

        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(2000);
        connectionManager.setDefaultMaxPerRoute(500);
        connectionManager.setDefaultConnectionConfig(connectionConfig);

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.of(5, TimeUnit.SECONDS))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig) // Применяем конфиг запроса
                .evictIdleConnections(TimeValue.ofSeconds(60))
                .disableAutomaticRetries()
                .build();

        var requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);

        ClientHttpRequestInterceptor loggingInterceptor = (request, body, execution) -> {
            log.debug("Request URI: {}", request.getURI());
            return execution.execute(request, body);
        };

        return RestClient.builder()
                .requestFactory(requestFactory)
                .requestInterceptor(loggingInterceptor)
                .build();
    }
}
