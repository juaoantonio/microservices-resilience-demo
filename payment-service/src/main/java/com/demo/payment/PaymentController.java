package com.demo.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentSimulationService simulationService;
    private final PaymentModeHolder modeHolder;

    @PostMapping("/payments")
    public ResponseEntity<PaymentSimulationResponse> processPayment(@RequestBody PaymentSimulationRequest request) {
        log.info("Received payment request for order={}", request.getOrderId());
        PaymentSimulationResponse response = simulationService.process(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/admin/mode")
    public ResponseEntity<String> setMode(@RequestBody ModeRequest request) {
        PaymentMode mode = PaymentMode.valueOf(request.getMode().toUpperCase());
        modeHolder.setMode(mode);
        log.info("Payment mode changed to {}", mode);
        return ResponseEntity.ok("Mode set to " + mode);
    }

    @GetMapping("/admin/mode")
    public ResponseEntity<String> getMode() {
        return ResponseEntity.ok(modeHolder.getMode().name());
    }
}
