package com.demo.order.service;

import com.demo.order.domain.Order;
import com.demo.order.dto.CreateOrderRequest;
import com.demo.order.dto.OrderResponse;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
public class ResilientPaymentCallService {

    private final PaymentExecutionService paymentExecutionService;

    @TimeLimiter(name = "paymentService", fallbackMethod = "timeLimiterFallback")
    public CompletableFuture<OrderResponse> callPayment(Order order, CreateOrderRequest request) {
        return CompletableFuture.supplyAsync(() -> paymentExecutionService.executePayment(order, request));
    }

    public CompletableFuture<OrderResponse> timeLimiterFallback(Order order, CreateOrderRequest request, Throwable ex) {
        return CompletableFuture.completedFuture(paymentExecutionService.paymentFallback(order, request, ex));
    }
}
