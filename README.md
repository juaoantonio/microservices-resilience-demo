# Microservices Resilience Demo

Projeto demo que demonstra padrões de resiliência em sistemas distribuídos usando Spring Boot 3, Resilience4j e Testcontainers.

## Arquitetura

```
Cliente → order-service (porta 8080) → payment-service (porta 8081)
                   ↓
             PostgreSQL (porta 5432)
```

**order-service** — serviço principal que cria pedidos, chama o payment-service e aplica os padrões de resiliência (Circuit Breaker, Retry, TimeLimiter).

**payment-service** — simulador com modos de comportamento configuráveis: NORMAL, DELAY, ERROR, FLAKY.

## Pré-requisitos

- Java 21
- Docker e Docker Compose

## Executando com Docker Compose

```bash
docker compose up --build
```

Os serviços sobem na seguinte ordem: PostgreSQL → payment-service → order-service.

Para derrubar tudo:

```bash
docker compose down
```

## Executando localmente (sem Docker Compose)

### 1. Subir o PostgreSQL

```bash
docker run -d --name postgres \
  -e POSTGRES_DB=orders \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 postgres:16-alpine
```

### 2. Subir o payment-service

```bash
./gradlew :payment-service:bootRun
```

### 3. Subir o order-service

```bash
./gradlew :order-service:bootRun
```

## Referência da API

### order-service (porta 8080)

**Criar pedido**
```bash
curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"productId":"prod-1","customerId":"cust-1","amount":99.99}'
```

**Consultar pedido**
```bash
curl http://localhost:8080/orders/1
```

**Status do circuit breaker**
```bash
curl http://localhost:8080/actuator/circuitbreakers
```

### payment-service (porta 8081)

**Alterar modo de simulação**
```bash
# NORMAL — resposta bem-sucedida (padrão)
curl -X POST http://localhost:8081/admin/mode \
  -H "Content-Type: application/json" -d '{"mode":"NORMAL"}'

# DELAY — atraso de 5s, provoca timeout no order-service
curl -X POST http://localhost:8081/admin/mode \
  -H "Content-Type: application/json" -d '{"mode":"DELAY"}'

# ERROR — sempre retorna HTTP 500
curl -X POST http://localhost:8081/admin/mode \
  -H "Content-Type: application/json" -d '{"mode":"ERROR"}'

# FLAKY — alterna entre erro e sucesso a cada chamada
curl -X POST http://localhost:8081/admin/mode \
  -H "Content-Type: application/json" -d '{"mode":"FLAKY"}'
```

## Cenários da demo

### Cenário 1 — Fluxo normal
1. Confirmar que o modo está em NORMAL
2. `POST /orders`
3. Resultado esperado: `status=CONFIRMED`, `paymentStatus=APPROVED`

### Cenário 2 — Latência / Timeout
1. Alterar modo para DELAY
2. `POST /orders`
3. Resultado esperado: TimeLimiter dispara em 2s → fallback ativado → `status=PENDING`, `paymentStatus=UNAVAILABLE`

### Cenário 3 — Erros intermitentes (Retry)
1. Alterar modo para FLAKY
2. Realizar múltiplos `POST /orders`
3. Resultado esperado: Retry tenta recuperar nas chamadas pares; pedidos confirmados eventualmente

### Cenário 4 — Indisponibilidade total (Circuit Breaker)
1. Alterar modo para ERROR
2. Realizar 3 ou mais `POST /orders` seguidos
3. Resultado esperado: circuit breaker abre após atingir o limiar de falhas
4. Verificar estado: `GET /actuator/circuitbreakers` → `state=OPEN`
5. Chamadas subsequentes retornam fallback (`status=PENDING`) sem atingir o payment-service

## Rodando os testes

```bash
# Todos os testes (requer Docker para Testcontainers)
./gradlew test

# Apenas order-service
./gradlew :order-service:test

# Apenas payment-service (não precisa de Docker)
./gradlew :payment-service:test
```

Cobertura de testes:
- **Unitários** — `PaymentSimulationServiceTest`, `PaymentFallbackHandlerTest`
- **Integração** — `PaymentControllerIntegrationTest`, `OrderPersistenceIntegrationTest` (PostgreSQL via Testcontainers), `DatabaseResilienceTest` (circuit breaker do banco)
- **End-to-end** — `OrderEndToEndTest` (WireMock + Testcontainers, 4 cenários)

## Configuração do Resilience4j (order-service)

### Chamadas ao payment-service

| Padrão          | Configuração                                                  |
|-----------------|---------------------------------------------------------------|
| Circuit Breaker | Abre com ≥ 50% de falhas em 5 chamadas (mínimo de 3 chamadas) |
| Retry           | Máximo de 3 tentativas com 500ms de espera entre elas         |
| TimeLimiter     | Timeout de 2s por chamada ao payment-service                  |
| Fallback        | Pedido salvo como PENDING com status de pagamento UNAVAILABLE |

### Chamadas ao banco de dados

| Padrão          | Configuração                                                                       |
|-----------------|------------------------------------------------------------------------------------|
| Circuit Breaker | Abre com ≥ 50% de falhas em 5 chamadas (mínimo de 3 chamadas)                     |
| Retry           | Máximo de 3 tentativas com 200ms de espera — somente `TransientDataAccessException` |
| Fallback        | Lança `DatabaseUnavailableException` → HTTP 503                                    |

## Observabilidade

| Endpoint                             | Descrição                                                        |
|--------------------------------------|------------------------------------------------------------------|
| `GET /actuator/health`               | Saúde do serviço + estado dos circuit breakers                   |
| `GET /actuator/metrics`              | Todas as métricas Micrometer                                     |
| `GET /actuator/circuitbreakers`      | Estados e estatísticas de todos os circuit breakers (payment + database) |
| `GET /actuator/circuitbreakerevents` | Eventos recentes dos circuit breakers                            |
