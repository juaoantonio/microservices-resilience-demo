package com.demo.payment;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PaymentSimulationResponse {
    private String paymentId;
    private String status;
    private String message;
}
