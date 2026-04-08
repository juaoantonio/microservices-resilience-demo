package com.demo.order.exception;

public class PaymentIntegrationException extends RuntimeException {
    public PaymentIntegrationException(String message) {
        super(message);
    }
    public PaymentIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
