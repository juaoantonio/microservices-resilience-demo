package com.demo.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class PaymentSimulationServiceTest {

    private PaymentModeHolder modeHolder;
    private PaymentSimulationService service;

    @BeforeEach
    void setUp() {
        modeHolder = new PaymentModeHolder();
        service = new PaymentSimulationService(modeHolder);
    }

    private PaymentSimulationRequest request() {
        PaymentSimulationRequest req = new PaymentSimulationRequest();
        req.setOrderId("order-1");
        req.setCustomerId("customer-1");
        req.setAmount(BigDecimal.TEN);
        return req;
    }

    @Test
    void normalMode_returnsApproved() {
        modeHolder.setMode(PaymentMode.NORMAL);
        PaymentSimulationResponse resp = service.process(request());
        assertThat(resp.getStatus()).isEqualTo("APPROVED");
        assertThat(resp.getPaymentId()).isNotBlank();
    }

    @Test
    void normalMode_generatesUniquePaymentIds() {
        modeHolder.setMode(PaymentMode.NORMAL);
        PaymentSimulationResponse r1 = service.process(request());
        PaymentSimulationResponse r2 = service.process(request());
        assertThat(r1.getPaymentId()).isNotEqualTo(r2.getPaymentId());
    }

    @Test
    void errorMode_throwsException() {
        modeHolder.setMode(PaymentMode.ERROR);
        assertThatThrownBy(() -> service.process(request()))
                .isInstanceOf(PaymentProcessingException.class)
                .hasMessageContaining("Simulated payment error");
    }

    @Test
    void flakyMode_firstCallFails() {
        modeHolder.setMode(PaymentMode.FLAKY);
        assertThatThrownBy(() -> service.process(request()))
                .isInstanceOf(PaymentProcessingException.class);
    }

    @Test
    void flakyMode_secondCallSucceeds() {
        modeHolder.setMode(PaymentMode.FLAKY);
        // call 1 (odd) → fails
        assertThatThrownBy(() -> service.process(request()))
                .isInstanceOf(PaymentProcessingException.class);
        // call 2 (even) → succeeds
        PaymentSimulationResponse resp = service.process(request());
        assertThat(resp.getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void modeReset_resetsCallCount() {
        modeHolder.setMode(PaymentMode.FLAKY);
        // call 1 fails
        assertThatThrownBy(() -> service.process(request()))
                .isInstanceOf(PaymentProcessingException.class);
        // reset to NORMAL resets counter
        modeHolder.setMode(PaymentMode.NORMAL);
        PaymentSimulationResponse resp = service.process(request());
        assertThat(resp.getStatus()).isEqualTo("APPROVED");
    }
}
