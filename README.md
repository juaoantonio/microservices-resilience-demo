# Microservices Resilience Demo

A demo project showing resilience patterns in a distributed system using Spring Boot 3, Resilience4j, and Testcontainers.

## Architecture

```
Client → order-service (port 8080) → payment-service (port 8081)
                   ↓
             PostgreSQL (port 5432)
```

**order-service** — main service that creates orders, calls payment-service, and applies resilience patterns (Circuit Breaker, Retry, TimeLimiter).

**payment-service** — simulator with configurable behavior modes: NORMAL, DELAY, ERROR, FLAKY.

## Prerequisites

- Java 21
- Docker (for Testcontainers and local infrastructure)

## Running locally

### 1. Start PostgreSQL
```bash
docker run -d --name postgres \
  -e POSTGRES_DB=orders \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 postgres:16-alpine
```

### 2. Start payment-service
```bash
./gradlew :payment-service:bootRun
```

### 3. Start order-service
```bash
./gradlew :order-service:bootRun
```

## API Reference

### order-service (port 8080)

**Create order**
```bash
curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"productId":"prod-1","customerId":"cust-1","amount":99.99}'
```

**Get order**
```bash
curl http://localhost:8080/orders/1
```

**Circuit breaker status**
```bash
curl http://localhost:8080/actuator/circuitbreakers
```

### payment-service (port 8081)

**Change simulation mode**
```bash
# NORMAL — successful response (default)
curl -X POST http://localhost:8081/admin/mode \
  -H "Content-Type: application/json" -d '{"mode":"NORMAL"}'

# DELAY — 5s delay, triggers order-service timeout
curl -X POST http://localhost:8081/admin/mode \
  -H "Content-Type: application/json" -d '{"mode":"DELAY"}'

# ERROR — always returns HTTP 500
curl -X POST http://localhost:8081/admin/mode \
  -H "Content-Type: application/json" -d '{"mode":"ERROR"}'

# FLAKY — alternates between error and success
curl -X POST http://localhost:8081/admin/mode \
  -H "Content-Type: application/json" -d '{"mode":"FLAKY"}'
```

## Demo Scenarios

### Scenario 1 — Normal flow
1. Ensure payment mode is NORMAL
2. `POST /orders`
3. Expected: `status=CONFIRMED`, `paymentStatus=APPROVED`

### Scenario 2 — Latency / Timeout
1. Set payment mode to DELAY
2. `POST /orders`
3. Expected: TimeLimiter triggers at 2s → fallback → `status=PENDING`, `paymentStatus=UNAVAILABLE`

### Scenario 3 — Intermittent errors (Retry)
1. Set payment mode to FLAKY
2. `POST /orders` repeatedly
3. Expected: Retry recovers on even-numbered attempts; occasional confirmed orders

### Scenario 4 — Full unavailability (Circuit Breaker)
1. Set payment mode to ERROR
2. `POST /orders` 3+ times in a row
3. Expected: Circuit breaker opens after failure threshold
4. Check state: `GET /actuator/circuitbreakers` → `state=OPEN`
5. Subsequent calls fail fast with fallback (`status=PENDING`) without reaching payment-service

## Running Tests

```bash
# All tests (requires Docker)
./gradlew test

# order-service only
./gradlew :order-service:test

# payment-service only (no Docker needed)
./gradlew :payment-service:test
```

Test coverage:
- **Unit tests** — `PaymentSimulationServiceTest`, `PaymentFallbackHandlerTest`
- **Integration tests** — `PaymentControllerIntegrationTest`, `OrderPersistenceIntegrationTest` (Testcontainers PostgreSQL)
- **End-to-end tests** — `OrderEndToEndTest` (WireMock + Testcontainers, all 4 scenarios)

## Resilience4j Configuration (order-service)

| Pattern | Setting |
|---|---|
| Circuit Breaker | Opens at ≥50% failures over 5 calls (min 3 calls required) |
| Retry | Max 3 attempts, 500ms between retries |
| TimeLimiter | 2s timeout per payment call |
| Fallback | Order saved as PENDING with UNAVAILABLE payment status |

## Observability

| Endpoint | Description |
|---|---|
| `GET /actuator/health` | Service health + circuit breaker state |
| `GET /actuator/metrics` | All Micrometer metrics |
| `GET /actuator/circuitbreakers` | Circuit breaker states and statistics |
| `GET /actuator/circuitbreakerevents` | Recent circuit breaker events |
