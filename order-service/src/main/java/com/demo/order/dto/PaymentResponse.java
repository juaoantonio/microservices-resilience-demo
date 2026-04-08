package com.demo.order.dto;

import lombok.Data;

@Data
public class PaymentResponse {
    private String paymentId;
    private String status;
    private String message;
}
