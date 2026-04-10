package com.demo.order.service;

import com.demo.order.domain.Order;
import com.demo.order.domain.OrderRepository;
import com.demo.order.exception.DatabaseUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResilientDatabaseService {

    private final OrderRepository orderRepository;

    @CircuitBreaker(name = "database", fallbackMethod = "saveFallback")
    @Retry(name = "database")
    public Order save(Order order) {
        return orderRepository.save(order);
    }

    @CircuitBreaker(name = "database", fallbackMethod = "findByIdFallback")
    @Retry(name = "database")
    public Optional<Order> findById(Long id) {
        return orderRepository.findById(id);
    }

    public Order saveFallback(Order order, Throwable ex) {
        log.error("Database circuit breaker triggered on save orderId={} cause={}", order.getId(), ex.getMessage());
        throw new DatabaseUnavailableException("Database unavailable", ex);
    }

    public Optional<Order> findByIdFallback(Long id, Throwable ex) {
        log.error("Database circuit breaker triggered on findById id={} cause={}", id, ex.getMessage());
        throw new DatabaseUnavailableException("Database unavailable", ex);
    }
}
