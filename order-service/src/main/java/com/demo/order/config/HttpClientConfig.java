package com.demo.order.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
public class HttpClientConfig {

    @Value("${payment.base-url}")
    private String paymentBaseUrl;

    @Bean
    public RestClient paymentRestClient() {
        return RestClient.builder()
                // Keep the request stack stable across environments instead of relying on classpath auto-detection.
                .requestFactory(new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .build()
                ))
                .baseUrl(paymentBaseUrl)
                .build();
    }
}
