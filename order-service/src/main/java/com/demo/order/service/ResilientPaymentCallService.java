package com.demo.order.service;

import com.demo.order.client.PaymentClient;
import com.demo.order.domain.Order;
import com.demo.order.domain.OrderStatus;
import com.demo.order.domain.PaymentStatus;
import com.demo.order.dto.CreateOrderRequest;
import com.demo.order.dto.OrderResponse;
import com.demo.order.dto.PaymentRequest;
import com.demo.order.dto.PaymentResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResilientPaymentCallService {

    private final PaymentClient paymentClient;
    private final ResilientDatabaseService resilientDatabaseService;
    private final PaymentFallbackHandler fallbackHandler;

    @CircuitBreaker(name = "paymentService", fallbackMethod = "paymentFallback")
    @TimeLimiter(name = "paymentService")
    @Retry(name = "paymentService")
    public CompletableFuture<OrderResponse> callPayment(Order order, CreateOrderRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Executing payment call for orderId={}", order.getId());
            PaymentRequest paymentRequest = PaymentRequest.builder()
                    .orderId(String.valueOf(order.getId()))
                    .customerId(request.getCustomerId())
                    .amount(request.getAmount())
                    .build();

            PaymentResponse paymentResponse = paymentClient.processPayment(paymentRequest);

            order.setStatus(OrderStatus.CONFIRMED);
            order.setPaymentStatus(PaymentStatus.APPROVED);
            order.setPaymentId(paymentResponse.getPaymentId());
            order.setMessage("Payment approved");
            resilientDatabaseService.save(order);

            log.info("Order confirmed orderId={} paymentId={}", order.getId(), paymentResponse.getPaymentId());

            return OrderResponse.builder()
                    .orderId(order.getId())
                    .status(order.getStatus().name())
                    .paymentStatus(order.getPaymentStatus().name())
                    .paymentId(paymentResponse.getPaymentId())
                    .message("Order created and payment approved")
                    .build();
        });
    }

    public CompletableFuture<OrderResponse> paymentFallback(Order order, CreateOrderRequest request, Throwable ex) {
        log.warn("Resilience fallback triggered for orderId={} cause={}", order.getId(), ex.getMessage());
        return CompletableFuture.completedFuture(fallbackHandler.handleFallback(order, ex));
    }
}
