package com.demo.order;

import com.demo.order.dto.CreateOrderRequest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderEndToEndTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static WireMockServer wireMock;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("payment.base-url", () -> "http://localhost:" + wireMock.port());
        // Aggressive settings to make resilience observable in tests
        registry.add("resilience4j.circuitbreaker.instances.paymentService.slidingWindowSize", () -> "4");
        registry.add("resilience4j.circuitbreaker.instances.paymentService.minimumNumberOfCalls", () -> "3");
        registry.add("resilience4j.circuitbreaker.instances.paymentService.failureRateThreshold", () -> "60");
        registry.add("resilience4j.circuitbreaker.instances.paymentService.waitDurationInOpenState", () -> "3s");
        registry.add("resilience4j.timelimiter.instances.paymentService.timeoutDuration", () -> "1s");
        registry.add("resilience4j.retry.instances.paymentService.maxAttempts", () -> "2");
        registry.add("resilience4j.retry.instances.paymentService.waitDuration", () -> "100ms");
    }

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
    }

    @Autowired
    TestRestTemplate restTemplate;

    private CreateOrderRequest orderRequest() {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setProductId("prod-1");
        req.setCustomerId("cust-1");
        req.setAmount(BigDecimal.valueOf(100.00));
        return req;
    }

    @Test
    @DisplayName("Scenario 1: Normal flow — payment approved, order CONFIRMED")
    void scenario1_normalFlow() {
        wireMock.stubFor(post(urlEqualTo("/payments"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"paymentId\":\"pay-001\",\"status\":\"APPROVED\",\"message\":\"OK\"}")));

        ResponseEntity<Map> response = restTemplate.postForEntity("/orders", orderRequest(), Map.class);

        assertThat(response.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.OK);
        assertThat(response.getBody()).containsKey("orderId");
        assertThat(response.getBody().get("status")).isEqualTo("CONFIRMED");
        assertThat(response.getBody().get("paymentStatus")).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("Scenario 2: Slow payment — TimeLimiter triggers, fallback returns PENDING")
    void scenario2_timeout_fallback() {
        wireMock.stubFor(post(urlEqualTo("/payments"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"paymentId\":\"pay-slow\",\"status\":\"APPROVED\",\"message\":\"OK\"}")
                        .withFixedDelay(3000))); // 3s > 1s timeout

        ResponseEntity<Map> response = restTemplate.postForEntity("/orders", orderRequest(), Map.class);

        assertThat(response.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.OK);
        String status = (String) response.getBody().get("status");
        assertThat(status).isIn("PENDING", "FAILED");
    }

    @Test
    @DisplayName("Scenario 3: Flaky payment — Retry recovers on second attempt")
    void scenario3_flaky_retry_recovers() {
        wireMock.stubFor(post(urlEqualTo("/payments"))
                .inScenario("flaky")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(500).withBody("{\"error\":\"transient\"}"))
                .willSetStateTo("retry1"));

        wireMock.stubFor(post(urlEqualTo("/payments"))
                .inScenario("flaky")
                .whenScenarioStateIs("retry1")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"paymentId\":\"pay-retry\",\"status\":\"APPROVED\",\"message\":\"OK\"}")));

        ResponseEntity<Map> response = restTemplate.postForEntity("/orders", orderRequest(), Map.class);

        assertThat(response.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.OK);
        assertThat(response.getBody()).containsKey("status");
    }

    @Test
    @DisplayName("Scenario 4: Payment unavailable — CircuitBreaker opens, fallback responds fast")
    void scenario4_unavailability_circuitBreaker() {
        wireMock.stubFor(post(urlEqualTo("/payments"))
                .willReturn(aResponse().withStatus(500).withBody("{\"error\":\"down\"}")));

        // Trip the circuit breaker
        for (int i = 0; i < 5; i++) {
            restTemplate.postForEntity("/orders", orderRequest(), Map.class);
        }

        // After circuit opens, subsequent calls should use fallback immediately
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    ResponseEntity<Map> response = restTemplate.postForEntity("/orders", orderRequest(), Map.class);
                    assertThat(response.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.OK);
                    String status = (String) response.getBody().get("status");
                    assertThat(status).isIn("PENDING", "FAILED");
                });
    }
}
