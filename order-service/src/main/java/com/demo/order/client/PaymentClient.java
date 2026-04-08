package com.demo.order.client;

import com.demo.order.dto.PaymentRequest;
import com.demo.order.dto.PaymentResponse;
import com.demo.order.exception.PaymentIntegrationException;
import com.demo.order.exception.PaymentUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentClient {

    private final RestClient paymentRestClient;

    public PaymentResponse processPayment(PaymentRequest request) {
        log.info("Calling payment service for orderId={}", request.getOrderId());
        try {
            PaymentResponse response = paymentRestClient.post()
                    .uri("/payments")
                    .body(request)
                    .retrieve()
                    .body(PaymentResponse.class);
            log.info("Payment service responded: status={}",
                    response != null ? response.getStatus() : "null");
            return response;
        } catch (ResourceAccessException ex) {
            log.error("Payment service unreachable: {}", ex.getMessage());
            throw new PaymentUnavailableException("Payment service is unavailable", ex);
        } catch (RestClientResponseException ex) {
            log.error("Payment service returned error: status={}", ex.getStatusCode());
            throw new PaymentIntegrationException("Payment service error: " + ex.getStatusCode(), ex);
        }
    }
}
