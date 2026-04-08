package com.demo.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentSimulationService {

    private final PaymentModeHolder modeHolder;

    public PaymentSimulationResponse process(PaymentSimulationRequest request) {
        PaymentMode mode = modeHolder.getMode();
        int count = modeHolder.incrementAndGetCallCount();
        log.info("Processing payment in mode={} call={}", mode, count);

        return switch (mode) {
            case NORMAL -> success(request);
            case DELAY -> delayed(request);
            case ERROR -> throw new PaymentProcessingException("Simulated payment error");
            case FLAKY -> {
                if (count % 2 != 0) {
                    throw new PaymentProcessingException("Flaky error on call " + count);
                }
                yield success(request);
            }
        };
    }

    private PaymentSimulationResponse success(PaymentSimulationRequest request) {
        return PaymentSimulationResponse.builder()
                .paymentId(UUID.randomUUID().toString())
                .status("APPROVED")
                .message("Payment approved for order " + request.getOrderId())
                .build();
    }

    private PaymentSimulationResponse delayed(PaymentSimulationRequest request) {
        try {
            log.info("Simulating delay of 5 seconds");
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return success(request);
    }
}
