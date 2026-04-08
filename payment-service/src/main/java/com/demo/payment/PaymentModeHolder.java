package com.demo.payment;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class PaymentModeHolder {

    private final AtomicReference<PaymentMode> mode = new AtomicReference<>(PaymentMode.NORMAL);
    private final AtomicInteger callCount = new AtomicInteger(0);

    public PaymentMode getMode() {
        return mode.get();
    }

    public void setMode(PaymentMode mode) {
        this.mode.set(mode);
        callCount.set(0);
    }

    public int incrementAndGetCallCount() {
        return callCount.incrementAndGet();
    }
}
