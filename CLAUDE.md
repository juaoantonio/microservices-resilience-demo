# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Run all services (Docker required)
docker compose up --build

# Run individual services locally
./gradlew :payment-service:bootRun   # port 8081
./gradlew :order-service:bootRun     # port 8080 (requires PostgreSQL on 5432)

# Build
./gradlew build

# Tests (all require Docker for Testcontainers)
./gradlew test
./gradlew :order-service:test
./gradlew :payment-service:test      # no Docker needed

# Run a single test class
./gradlew :order-service:test --tests "com.demo.order.OrderEndToEndTest"
./gradlew :order-service:test --tests "com.demo.order.PaymentFallbackHandlerTest"
```

## Architecture

Two Spring Boot 3 services demonstrating Resilience4j patterns:

```
Client → order-service (8080) → payment-service (8081)
              ↓
         PostgreSQL (5432)
```

**order-service** — creates and persists orders, calls payment-service with resilience decorators applied. The resilience stack in `ResilientPaymentCallService` stacks three annotations: `@CircuitBreaker` + `@TimeLimiter` + `@Retry` on the same `CompletableFuture` method. Execution order (outermost to innermost): CircuitBreaker → TimeLimiter → Retry → actual call. The shared fallback method `paymentFallback` handles all three failure modes — timeout, retry exhaustion, and open circuit — by delegating to `PaymentFallbackHandler`, which saves the order as `PENDING/UNAVAILABLE` and returns the response.

All database calls go through `ResilientDatabaseService`, which wraps `OrderRepository.save` and `findById` with `@CircuitBreaker` + `@Retry` (instance `database`). No `@TimeLimiter` — it requires `CompletableFuture` which conflicts with synchronous `@Transactional`. Fallback throws `DatabaseUnavailableException` → HTTP 503.

**payment-service** — stateful simulator with four modes (`NORMAL`, `DELAY`, `ERROR`, `FLAKY`) controlled via `POST /admin/mode`. Mode is held in `PaymentModeHolder` (a thread-safe singleton). Used by the demo and by tests that need a real HTTP server.

### Key design decisions

- `PaymentClient` translates `ResourceAccessException` → `PaymentUnavailableException` and HTTP errors → `PaymentIntegrationException`. Only these two exception types are configured as retry triggers in `application.yml`.
- `ResilientDatabaseService` retries only `TransientDataAccessException` (covers pool exhaustion, deadlocks, query timeouts). `NonTransientDataAccessException` (constraint violations) is intentionally excluded.
- Resilience4j config lives entirely in `order-service/src/main/resources/application.yml` under `resilience4j.*`. Both `paymentService` and `database` instances are defined there. Tests override these values with aggressive thresholds via `@DynamicPropertySource`.
- `OrderEndToEndTest` uses WireMock (not the real payment-service) alongside a Testcontainers PostgreSQL instance. Circuit breaker thresholds are overridden per-test to make resilience behavior observable without many requests.
- `DatabaseResilienceTest` uses `@MockBean OrderRepository` to inject `TransientDataAccessException` and verifies `verify(orderRepository, times(3)).save(any())` — proving the 4th call was blocked at the AOP proxy level.
- `OrderPersistenceIntegrationTest` uses Testcontainers PostgreSQL with a separate `order-service/src/test/resources/application.yml` that disables the real payment URL.