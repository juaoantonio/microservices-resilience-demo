package com.demo.order;

import com.demo.order.dto.CreateOrderRequest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.MethodOrderer.MethodName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodName.class)
class OrderEndToEndTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

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
        circuitBreakerRegistry.circuitBreaker("paymentService").reset();
    }

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    RetryRegistry retryRegistry;

    private CreateOrderRequest orderRequest() {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setProductId("prod-1");
        req.setCustomerId("cust-1");
        req.setAmount(BigDecimal.valueOf(100.00));
        return req;
    }

    @Test
    @DisplayName("Cenário 1: Fluxo normal — pagamento aprovado, pedido CONFIRMADO")
    void scenario1_normalFlow() {
        wireMock.stubFor(post(urlEqualTo("/payments"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"paymentId\":\"pay-001\",\"status\":\"APPROVED\",\"message\":\"OK\"}")));

        ResponseEntity<Map> response = restTemplate.postForEntity("/orders", orderRequest(), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKey("orderId");
        assertThat(response.getBody().get("status")).isEqualTo("CONFIRMED");
        assertThat(response.getBody().get("paymentStatus")).isEqualTo("APPROVED");
        assertThat(response.getBody().get("paymentId")).isEqualTo("pay-001");
        assertThat(response.getBody().get("message")).isEqualTo("Order created and payment approved");
    }

    @Test
    @DisplayName("Cenário 2: Pagamento lento — TimeLimiter dispara, fallback retorna PENDING/UNAVAILABLE")
    void scenario2_timeout_fallback() {
        wireMock.stubFor(post(urlEqualTo("/payments"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"paymentId\":\"pay-slow\",\"status\":\"APPROVED\",\"message\":\"OK\"}")
                        .withFixedDelay(3000))); // 3s > 1s timeout

        ResponseEntity<Map> response = restTemplate.postForEntity("/orders", orderRequest(), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status")).isEqualTo("PENDING");
        assertThat(response.getBody().get("paymentStatus")).isEqualTo("UNAVAILABLE");
        assertThat(response.getBody().get("message")).isEqualTo("Payment service temporarily unavailable. Order is pending.");
        assertThat(response.getBody().get("paymentId")).isNull();
    }

    @Test
    @DisplayName("Cenário 3: Pagamento instável — Retry recupera na segunda tentativa")
    void scenario3_flaky_retry_recovers() {
        wireMock.stubFor(post(urlEqualTo("/payments"))
                .inScenario("flaky-payment")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"temporary failure\"}"))
                .willSetStateTo("SECOND_ATTEMPT"));

        wireMock.stubFor(post(urlEqualTo("/payments"))
                .inScenario("flaky-payment")
                .whenScenarioStateIs("SECOND_ATTEMPT")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"paymentId\":\"pay-002\",\"status\":\"APPROVED\",\"message\":\"OK\"}")));

        ResponseEntity<Map> response = restTemplate.postForEntity("/orders", orderRequest(), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKey("orderId");
        assertThat(response.getBody().get("status")).isEqualTo("CONFIRMED");
        assertThat(response.getBody().get("paymentStatus")).isEqualTo("APPROVED");
        assertThat(response.getBody().get("paymentId")).isEqualTo("pay-002");
        assertThat(response.getBody().get("message")).isEqualTo("Order created and payment approved");

        wireMock.verify(2, postRequestedFor(urlEqualTo("/payments")));
    }

    @Test
    @DisplayName("Cenário 4: Pagamento indisponível — CircuitBreaker abre, fallback responde rapidamente")
    void scenario4_unavailability_circuitBreaker() {
        wireMock.stubFor(post(urlEqualTo("/payments"))
                .willReturn(aResponse().withStatus(500).withBody("{\"error\":\"down\"}")));

        // Trip the circuit breaker
        for (int i = 0; i < 5; i++) {
            restTemplate.postForEntity("/orders", orderRequest(), Map.class);
        }

        // After circuit opens, subsequent calls must use fallback immediately (no WireMock hit)
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    ResponseEntity<Map> response = restTemplate.postForEntity("/orders", orderRequest(), Map.class);
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                    assertThat(response.getBody().get("status")).isEqualTo("PENDING");
                    assertThat(response.getBody().get("paymentStatus")).isEqualTo("UNAVAILABLE");
                    assertThat(response.getBody().get("message")).isEqualTo("Payment service temporarily unavailable. Order is pending.");
                });
    }
}
