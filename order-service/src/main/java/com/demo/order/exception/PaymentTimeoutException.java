package com.demo.order.exception;

public class PaymentTimeoutException extends RuntimeException {
    public PaymentTimeoutException(String message) {
        super(message);
    }
    public PaymentTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
