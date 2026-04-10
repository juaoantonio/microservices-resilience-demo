package com.demo.order;

import com.demo.order.domain.Order;
import com.demo.order.domain.OrderStatus;
import com.demo.order.dto.OrderResponse;
import com.demo.order.service.PaymentFallbackHandler;
import com.demo.order.service.ResilientDatabaseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentFallbackHandlerTest {

    @Mock
    ResilientDatabaseService resilientDatabaseService;

    @InjectMocks
    PaymentFallbackHandler fallbackHandler;

    @Test
    @DisplayName("Fallback deve definir status PENDING e pagamento UNAVAILABLE")
    void fallback_setsPendingStatusAndUnavailablePayment() {
        Order order = Order.builder()
                .id(1L)
                .productId("prod-1")
                .customerId("cust-1")
                .amount(BigDecimal.TEN)
                .status(OrderStatus.CREATED)
                .build();

        when(resilientDatabaseService.save(any())).thenReturn(order);

        OrderResponse response = fallbackHandler.handleFallback(order, new RuntimeException("timeout"));

        assertThat(response.getStatus()).isEqualTo("PENDING");
        assertThat(response.getPaymentStatus()).isEqualTo("UNAVAILABLE");
        assertThat(response.getMessage()).containsIgnoringCase("pending");
    }

    @Test
    @DisplayName("Fallback deve retornar o id do pedido e mensagem de degradação")
    void fallback_returnsOrderIdAndDegradationMessage() {
        Order order = Order.builder()
                .id(2L)
                .productId("prod-x")
                .customerId("cust-x")
                .amount(BigDecimal.ONE)
                .status(OrderStatus.CREATED)
                .build();

        when(resilientDatabaseService.save(any())).thenReturn(order);

        OrderResponse response = fallbackHandler.handleFallback(order, new RuntimeException("circuit open"));

        assertThat(response.getMessage()).isNotBlank();
        assertThat(response.getOrderId()).isEqualTo(2L);
    }
}
