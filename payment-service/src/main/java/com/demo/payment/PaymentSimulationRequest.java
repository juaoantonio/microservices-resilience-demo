package com.demo.payment;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PaymentSimulationRequest {
    private String orderId;
    private String customerId;
    private BigDecimal amount;
}
