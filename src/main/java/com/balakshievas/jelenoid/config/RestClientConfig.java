package com.balakshievas.jelenoid.config;

import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.ConnectionReuseStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    private static final Logger log = LoggerFactory.getLogger(RestClientConfig.class);

    @Bean
    public RestClient restClient() {
        ConnectionReuseStrategy noReuseStrategy = (request, response, context) -> false;
        var httpClient = HttpClientBuilder.create()
                .setConnectionReuseStrategy(noReuseStrategy)
                .build();
        var requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);


        ClientHttpRequestInterceptor loggingInterceptor = (HttpRequest request, byte[] body, ClientHttpRequestExecution execution) -> {
            log.info("======================= RESTCLIENT REQUEST =======================");
            log.info("URI: {}", request.getURI());
            log.info("Method: {}", request.getMethod());
            log.info("Headers: {}", request.getHeaders());
            if (body.length > 0) {
                log.info("Request Body: {}", new String(body));
            } else {
                log.info("Request Body: [EMPTY]");
            }
            log.info("==================================================================");
            return execution.execute(request, body);
        };

        return RestClient.builder()
                .requestFactory(requestFactory)
                .requestInterceptor(loggingInterceptor)
                .build();
    }
}
