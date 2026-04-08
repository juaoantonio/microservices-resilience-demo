package com.demo.order.service;

import com.demo.order.domain.Order;
import com.demo.order.domain.OrderRepository;
import com.demo.order.domain.OrderStatus;
import com.demo.order.domain.PaymentStatus;
import com.demo.order.dto.CreateOrderRequest;
import com.demo.order.dto.OrderResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderApplicationService {

    private final OrderRepository orderRepository;
    private final ResilientPaymentCallService resilientPaymentCallService;

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        log.info("Creating order for customerId={} productId={}", request.getCustomerId(), request.getProductId());

        Order order = Order.builder()
                .productId(request.getProductId())
                .customerId(request.getCustomerId())
                .amount(request.getAmount())
                .status(OrderStatus.CREATED)
                .build();
        order = orderRepository.save(order);

        try {
            return resilientPaymentCallService.callPayment(order, request).get();
        } catch (Exception ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            log.error("Unrecoverable error for orderId={}: {}", order.getId(), cause.getMessage());
            order.setStatus(OrderStatus.FAILED);
            order.setPaymentStatus(PaymentStatus.ERROR);
            order.setMessage("Order failed: " + cause.getMessage());
            orderRepository.save(order);
            return OrderResponse.builder()
                    .orderId(order.getId())
                    .status(order.getStatus().name())
                    .paymentStatus(order.getPaymentStatus().name())
                    .message(order.getMessage())
                    .build();
        }
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found: " + id));
        return OrderResponse.builder()
                .orderId(order.getId())
                .status(order.getStatus().name())
                .paymentStatus(order.getPaymentStatus() != null ? order.getPaymentStatus().name() : null)
                .paymentId(order.getPaymentId())
                .message(order.getMessage())
                .build();
    }
}
