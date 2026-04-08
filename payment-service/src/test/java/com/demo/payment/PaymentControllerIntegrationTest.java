package com.demo.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PaymentModeHolder modeHolder;

    private String requestBody() throws Exception {
        PaymentSimulationRequest req = new PaymentSimulationRequest();
        req.setOrderId("order-test");
        req.setCustomerId("cust-1");
        req.setAmount(BigDecimal.TEN);
        return objectMapper.writeValueAsString(req);
    }

    @Test
    void normalMode_returns200WithApproved() throws Exception {
        modeHolder.setMode(PaymentMode.NORMAL);
        mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.paymentId").isNotEmpty());
    }

    @Test
    void errorMode_returns500() throws Exception {
        modeHolder.setMode(PaymentMode.ERROR);
        mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void setMode_changesMode() throws Exception {
        ModeRequest modeReq = new ModeRequest();
        modeReq.setMode("DELAY");
        mockMvc.perform(post("/admin/mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(modeReq)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/mode"))
                .andExpect(status().isOk())
                .andExpect(content().string("DELAY"));
    }

    @Test
    void flakyMode_alternatesBehavior() throws Exception {
        modeHolder.setMode(PaymentMode.FLAKY);
        // First call (odd) should fail
        mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isInternalServerError());
        // Second call (even) should succeed
        mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }
}
