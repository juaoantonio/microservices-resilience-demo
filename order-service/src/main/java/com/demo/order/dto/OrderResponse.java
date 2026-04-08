package com.demo.order.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OrderResponse {
    private Long orderId;
    private String status;
    private String paymentStatus;
    private String message;
    private String paymentId;
}
