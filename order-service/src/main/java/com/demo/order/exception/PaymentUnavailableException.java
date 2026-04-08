package com.demo.order.exception;

public class PaymentUnavailableException extends RuntimeException {
    public PaymentUnavailableException(String message) {
        super(message);
    }
    public PaymentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
