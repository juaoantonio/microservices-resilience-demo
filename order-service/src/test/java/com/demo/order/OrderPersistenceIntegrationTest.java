package com.demo.order;

import com.demo.order.domain.Order;
import com.demo.order.domain.OrderRepository;
import com.demo.order.domain.OrderStatus;
import com.demo.order.domain.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderPersistenceIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("payment.base-url", () -> "http://localhost:9999");
    }

    @Autowired
    OrderRepository orderRepository;

    @Test
    @DisplayName("Deve persistir pedido com todos os campos preenchidos corretamente")
    void persistsOrderWithAllFields() {
        Order order = Order.builder()
                .productId("prod-1")
                .customerId("cust-1")
                .amount(BigDecimal.valueOf(99.99))
                .status(OrderStatus.CONFIRMED)
                .paymentStatus(PaymentStatus.APPROVED)
                .paymentId("pay-abc")
                .message("Payment approved")
                .build();

        Order saved = orderRepository.save(order);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(saved.getPaymentStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(saved.getPaymentId()).isEqualTo("pay-abc");
    }

    @Test
    @DisplayName("Deve persistir pedido com status PENDING e pagamento UNAVAILABLE")
    void persistsPendingOrder() {
        Order order = Order.builder()
                .productId("prod-2")
                .customerId("cust-2")
                .amount(BigDecimal.valueOf(50.00))
                .status(OrderStatus.PENDING)
                .paymentStatus(PaymentStatus.UNAVAILABLE)
                .message("Payment service temporarily unavailable. Order is pending.")
                .build();

        Order saved = orderRepository.save(order);

        assertThat(saved.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(saved.getPaymentStatus()).isEqualTo(PaymentStatus.UNAVAILABLE);
    }
}
