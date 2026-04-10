package com.demo.order;

import com.demo.order.domain.Order;
import com.demo.order.domain.OrderStatus;
import com.demo.order.exception.DatabaseUnavailableException;
import com.demo.order.service.ResilientDatabaseService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DatabaseResilienceTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("payment.base-url", () -> "http://localhost:9999");
        // Short timeout so failed connection attempts don't block for 30s (HikariCP default)
        registry.add("spring.datasource.hikari.connection-timeout", () -> "1000");
        // Aggressive thresholds to trip the circuit quickly
        registry.add("resilience4j.circuitbreaker.instances.database.slidingWindowSize", () -> "3");
        registry.add("resilience4j.circuitbreaker.instances.database.minimumNumberOfCalls", () -> "3");
        registry.add("resilience4j.circuitbreaker.instances.database.failureRateThreshold", () -> "60");
        registry.add("resilience4j.circuitbreaker.instances.database.waitDurationInOpenState", () -> "60s");
        registry.add("resilience4j.retry.instances.database.maxAttempts", () -> "2");
        registry.add("resilience4j.retry.instances.database.waitDuration", () -> "100ms");
    }

    @Autowired
    ResilientDatabaseService resilientDatabaseService;

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    private Order dummyOrder() {
        return Order.builder()
                .productId("prod-1")
                .customerId("cust-1")
                .amount(BigDecimal.TEN)
                .status(OrderStatus.CREATED)
                .build();
    }

    @Test
    @DisplayName("Circuit breaker opens after database container becomes unavailable")
    void circuitBreaker_opensWhenDatabaseBecomesUnavailable() {
        // Confirm DB is healthy before the outage
        assertThatCode(() -> resilientDatabaseService.save(dummyOrder()))
                .doesNotThrowAnyException();

        // Simulate database outage
        postgres.stop();

        // Each failing call exhausts its retries (maxAttempts=2), then the CB records a failure.
        // After 3 total calls (1 success + 2 failures = minimum reached), the circuit opens.
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> resilientDatabaseService.save(dummyOrder()))
                    .isInstanceOf(DatabaseUnavailableException.class);
        }

        CircuitBreaker db = circuitBreakerRegistry.circuitBreaker("database");
        assertThat(db.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // With the circuit OPEN, calls are rejected immediately without touching the DB
        assertThat(db.getMetrics().getNumberOfNotPermittedCalls()).isGreaterThanOrEqualTo(1);
    }
}
