package com.demo.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateOrderRequest {

    @NotBlank
    private String productId;

    @NotBlank
    private String customerId;

    @NotNull
    @Positive
    private BigDecimal amount;
}
