package com.demo.order.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfig {

    @Value("${payment.base-url}")
    private String paymentBaseUrl;

    @Bean
    public RestClient paymentRestClient() {
        return RestClient.builder()
                .baseUrl(paymentBaseUrl)
                .build();
    }
}
