package com.demo.order.service;

import com.demo.order.domain.Order;
import com.demo.order.dto.OrderResponse;
import org.springframework.stereotype.Component;

@Component
public class OrderMapper {

    public OrderResponse toResponse(Order order) {
        return OrderResponse.builder()
                .orderId(order.getId())
                .status(order.getStatus().name())
                .paymentStatus(order.getPaymentStatus() != null ? order.getPaymentStatus().name() : null)
                .paymentId(order.getPaymentId())
                .message(order.getMessage())
                .build();
    }
}
