package com.demo.order.service;

import com.demo.order.domain.Order;
import com.demo.order.domain.OrderRepository;
import com.demo.order.domain.OrderStatus;
import com.demo.order.domain.PaymentStatus;
import com.demo.order.dto.OrderResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentFallbackHandler {

    private final OrderRepository orderRepository;

    public OrderResponse handleFallback(Order order, Throwable ex) {
        log.warn("Fallback triggered for orderId={} reason={}", order.getId(), ex.getMessage());
        order.setStatus(OrderStatus.PENDING);
        order.setPaymentStatus(PaymentStatus.UNAVAILABLE);
        order.setMessage("Payment service temporarily unavailable. Order is pending.");
        orderRepository.save(order);
        return OrderResponse.builder()
                .orderId(order.getId())
                .status(order.getStatus().name())
                .paymentStatus(order.getPaymentStatus().name())
                .message(order.getMessage())
                .build();
    }
}
