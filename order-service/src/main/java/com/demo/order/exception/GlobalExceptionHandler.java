package com.demo.order.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PaymentTimeoutException.class)
    public ResponseEntity<Map<String, String>> handleTimeout(PaymentTimeoutException ex) {
        log.warn("Payment timeout: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(Map.of("error", "Payment service timed out", "detail", ex.getMessage()));
    }

    @ExceptionHandler(PaymentUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleUnavailable(PaymentUnavailableException ex) {
        log.warn("Payment unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Payment service unavailable", "detail", ex.getMessage()));
    }

    @ExceptionHandler(PaymentIntegrationException.class)
    public ResponseEntity<Map<String, String>> handleIntegration(PaymentIntegrationException ex) {
        log.warn("Payment integration error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "Payment integration error", "detail", ex.getMessage()));
    }

    @ExceptionHandler(DatabaseUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleDatabaseUnavailable(DatabaseUnavailableException ex) {
        log.error("Database unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Database unavailable", "detail", ex.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleGeneric(RuntimeException ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal error",
                        "detail", ex.getMessage() != null ? ex.getMessage() : "unknown"));
    }
}
