# Guia Técnico — Microservices Resilience Demo

## Sumário

1. [Visão geral](#1-visão-geral)
2. [Estrutura do projeto](#2-estrutura-do-projeto)
3. [payment-service](#3-payment-service)
4. [order-service](#4-order-service)
5. [Padrões de resiliência](#5-padrões-de-resiliência)
6. [Fluxos da aplicação](#6-fluxos-da-aplicação)
7. [Camada de persistência](#7-camada-de-persistência)
8. [Testes](#8-testes)
9. [Resiliência do banco de dados](#9-resiliência-do-banco-de-dados)

---

## 1. Visão geral

O projeto demonstra como aplicar padrões de resiliência em uma arquitetura de microsserviços. O cenário simula um sistema de pedidos (`order-service`) que depende de um serviço externo de pagamentos (`payment-service`) para confirmar transações.

O objetivo é mostrar, de forma reproduzível, o que acontece quando essa dependência se torna lenta, intermitente ou totalmente indisponível — e como a aplicação se comporta de forma controlada em cada um desses casos.

```
┌─────────┐     POST /orders      ┌───────────────┐    POST /payments    ┌─────────────────┐
│ Cliente │ ───────────────────▶  │ order-service │ ──────────────────▶  │ payment-service │
└─────────┘                       └───────────────┘                       └─────────────────┘
                                          │
                                          ▼
                                    ┌──────────┐
                                    │ PostgreSQL│
                                    └──────────┘
```

---

## 2. Estrutura do projeto

```
microservices-resilience/
├── order-service/                   # Serviço principal
│   └── src/main/java/com/demo/order/
│       ├── OrderServiceApplication.java
│       ├── controller/
│       │   └── OrderController.java
│       ├── service/
│       │   ├── OrderApplicationService.java
│       │   ├── ResilientPaymentCallService.java
│       │   ├── ResilientDatabaseService.java
│       │   ├── PaymentFallbackHandler.java
│       │   └── OrderMapper.java
│       ├── client/
│       │   └── PaymentClient.java
│       ├── domain/
│       │   ├── Order.java
│       │   ├── OrderRepository.java
│       │   ├── OrderStatus.java
│       │   └── PaymentStatus.java
│       ├── dto/
│       │   ├── CreateOrderRequest.java
│       │   ├── OrderResponse.java
│       │   ├── PaymentRequest.java
│       │   └── PaymentResponse.java
│       ├── exception/
│       │   ├── GlobalExceptionHandler.java
│       │   ├── DatabaseUnavailableException.java
│       │   ├── PaymentIntegrationException.java
│       │   ├── PaymentTimeoutException.java
│       │   └── PaymentUnavailableException.java
│       └── config/
│           └── HttpClientConfig.java
│
├── payment-service/                 # Simulador de pagamentos
│   └── src/main/java/com/demo/payment/
│       ├── PaymentServiceApplication.java
│       ├── PaymentController.java
│       ├── PaymentSimulationService.java
│       ├── PaymentModeHolder.java
│       ├── PaymentMode.java
│       ├── PaymentSimulationRequest.java
│       ├── PaymentSimulationResponse.java
│       ├── ModeRequest.java
│       ├── PaymentProcessingException.java
│       └── PaymentExceptionHandler.java
│
├── docker-compose.yml
├── README.md
└── GUIDE.md
```

---

## 3. payment-service

### Propósito

Simula um provedor externo de pagamentos. Não possui banco de dados nem estado persistido — serve exclusivamente como ferramenta de teste para provocar os cenários de resiliência no `order-service`.

### Componentes

#### `PaymentMode`
Enumeração com os quatro modos de simulação disponíveis:

| Modo     | Comportamento                                                             |
|----------|---------------------------------------------------------------------------|
| `NORMAL` | Retorna pagamento aprovado imediatamente                                  |
| `DELAY`  | Aguarda 5 segundos antes de responder (provoca timeout)                   |
| `ERROR`  | Lança exceção, resultando em HTTP 500                                     |
| `FLAKY`  | Alterna entre erro e sucesso a cada chamada (ímpar = erro, par = sucesso) |

#### `PaymentModeHolder`
Componente Spring que mantém o modo ativo em memória usando `AtomicReference<PaymentMode>`. Também mantém um contador de chamadas (`AtomicInteger`) que é zerado toda vez que o modo é alterado — necessário para o comportamento alternado do modo `FLAKY`.

#### `PaymentSimulationService`
Contém a lógica de cada modo. Usa `switch` com expressão de bloco para tratar os quatro casos. O modo `DELAY` bloqueia a thread por 5 segundos com `Thread.sleep`, simulando latência real de rede ou processamento.

#### `PaymentController`
Expõe três endpoints:

| Método | Rota          | Descrição                                   |
|--------|---------------|---------------------------------------------|
| `POST` | `/payments`   | Processa um pagamento conforme o modo ativo |
| `POST` | `/admin/mode` | Altera o modo de simulação                  |
| `GET`  | `/admin/mode` | Consulta o modo atual                       |

#### `PaymentExceptionHandler`
Captura `PaymentProcessingException` e retorna HTTP 500 com corpo JSON contendo a mensagem de erro.

---

## 4. order-service

### Propósito

Serviço principal da demo. Recebe requisições de criação de pedidos, persiste o pedido no banco, chama o `payment-service` e aplica os padrões de resiliência nessa chamada. O resultado (confirmado, pendente ou com falha) é persistido e retornado ao cliente.

### Componentes

#### `OrderController`
Ponto de entrada HTTP. Valida o corpo da requisição com Bean Validation (`@Valid`) e delega para o `OrderApplicationService`.

| Método | Rota           | Descrição                  |
|--------|----------------|----------------------------|
| `POST` | `/orders`      | Cria um novo pedido        |
| `GET`  | `/orders/{id}` | Consulta um pedido pelo ID |

#### `OrderApplicationService`
Orquestra o fluxo de criação de pedido:

1. Cria e persiste o pedido com status `CREATED`
2. Delega a chamada ao pagamento para `ResilientPaymentCallService`
3. Aguarda o resultado com `.get()`
4. Em caso de erro não tratado pelo fallback, persiste o pedido como `FAILED`

#### `ResilientPaymentCallService`
Bean separado que concentra as anotações de resiliência do Resilience4j. A separação em um bean próprio é necessária porque as anotações `@CircuitBreaker`, `@TimeLimiter` e `@Retry` funcionam via proxy AOP — se estivessem no mesmo bean que chama o método, o proxy não seria acionado.

O método `callPayment` retorna `CompletableFuture<OrderResponse>`, exigência do `@TimeLimiter` para poder cancelar a execução após o timeout.

O método `paymentFallback` é acionado automaticamente pelo Resilience4j quando qualquer um dos três mecanismos (circuit breaker aberto, timeout ou exaustão de retries) resulta em falha. Sua assinatura deve corresponder exatamente à do método principal, acrescida do parâmetro `Throwable`.

#### `PaymentFallbackHandler`
Encapsula a lógica de fallback: atualiza o pedido para `PENDING` / `UNAVAILABLE`, persiste e retorna a resposta degradada. Separado do serviço para facilitar testes unitários.

#### `ResilientDatabaseService`
Aplica Circuit Breaker e Retry a todas as operações de banco de dados. Envolve o `OrderRepository` expondo dois métodos — `save` e `findById` — decorados com `@CircuitBreaker` e `@Retry` (instância `database`). Quando o circuito abre ou os retries se esgotam, o fallback lança `DatabaseUnavailableException`, que o `GlobalExceptionHandler` mapeia para HTTP 503.

Não usa `@TimeLimiter` porque esse padrão exige `CompletableFuture`, que é incompatível com chamadas síncronas dentro de uma transação. Timeouts de conexão e query são configurados no nível do pool JDBC (HikariCP).

O bean não é `@Transactional`: a fronteira transacional permanece no `OrderApplicationService`, que é o dono da transação. Se uma operação falhar dentro de um rollback já em andamento, a `DatabaseUnavailableException` propaga normalmente.

#### `PaymentClient`
Realiza a chamada HTTP ao `payment-service` usando o `RestClient` do Spring 6. Trata dois tipos de falha:

- `ResourceAccessException` → serviço inacessível → `PaymentUnavailableException`
- `RestClientResponseException` → resposta de erro HTTP → `PaymentIntegrationException`

#### `HttpClientConfig`
Cria o bean `RestClient` com a URL base do `payment-service` lida da propriedade `payment.base-url`. A URL é injetada via `@Value`, o que permite sobrescrevê-la nos testes via `@DynamicPropertySource`.

#### `GlobalExceptionHandler`
Traduz exceções de domínio em respostas HTTP legíveis:

| Exceção                        | HTTP                      |
|--------------------------------|---------------------------|
| `PaymentTimeoutException`      | 504 Gateway Timeout       |
| `PaymentUnavailableException`  | 503 Service Unavailable   |
| `DatabaseUnavailableException` | 503 Service Unavailable   |
| `PaymentIntegrationException`  | 502 Bad Gateway           |
| `RuntimeException`             | 500 Internal Server Error |

#### Enumerações de status

**`OrderStatus`** — estado do pedido:

| Valor       | Significado                                            |
|-------------|--------------------------------------------------------|
| `CREATED`   | Pedido criado, aguardando processamento do pagamento   |
| `CONFIRMED` | Pagamento aprovado, pedido confirmado                  |
| `FAILED`    | Falha irrecuperável no processamento                   |
| `PENDING`   | Pagamento indisponível, pedido aguarda reprocessamento |

**`PaymentStatus`** — resultado da tentativa de pagamento:

| Valor         | Significado                                 |
|---------------|---------------------------------------------|
| `APPROVED`    | Pagamento aprovado pelo provedor            |
| `DECLINED`    | Pagamento recusado pelo provedor            |
| `UNAVAILABLE` | Serviço de pagamento inacessível (fallback) |
| `TIMEOUT`     | Timeout na chamada ao pagamento             |
| `ERROR`       | Erro genérico no processamento              |

---

## 5. Padrões de resiliência

Todos os padrões são configurados em `order-service/src/main/resources/application.yml` e aplicados via anotações no `ResilientPaymentCallService`.

### Circuit Breaker

Monitora as chamadas ao `payment-service` e, após atingir o limiar de falhas, abre o circuito e passa a retornar o fallback diretamente, sem chegar à dependência.

```
CLOSED ──(falhas ≥ 50% em 5 calls)──▶ OPEN ──(após 10s)──▶ HALF-OPEN
  ▲                                                               │
  └──────────────(2 chamadas de teste com sucesso)────────────────┘
```

| Parâmetro                               | Valor | Descrição                                 |
|-----------------------------------------|-------|-------------------------------------------|
| `slidingWindowSize`                     | 5     | Janela de chamadas avaliadas              |
| `minimumNumberOfCalls`                  | 3     | Mínimo para começar a avaliar             |
| `failureRateThreshold`                  | 50    | % de falhas para abrir o circuito         |
| `waitDurationInOpenState`               | 10s   | Tempo em aberto antes de tentar HALF-OPEN |
| `permittedNumberOfCallsInHalfOpenState` | 2     | Chamadas de teste no estado HALF-OPEN     |

### Retry

Tenta novamente chamadas que falham com exceções consideradas transitórias (`PaymentIntegrationException`, `PaymentUnavailableException`). Não faz sentido aplicar retry em timeouts, pois o `@TimeLimiter` já cancelou a execução.

| Parâmetro      | Valor |
|----------------|-------|
| `maxAttempts`  | 3     |
| `waitDuration` | 500ms |

### TimeLimiter

Limita o tempo de espera da chamada ao `payment-service`. Após o timeout, cancela o `CompletableFuture` e aciona o fallback. Funciona em conjunto com execução assíncrona obrigatoriamente.

| Parâmetro             | Valor |
|-----------------------|-------|
| `timeoutDuration`     | 2s    |
| `cancelRunningFuture` | true  |

### Fallback

Acionado por qualquer um dos três mecanismos acima. Persiste o pedido como `PENDING` com `paymentStatus = UNAVAILABLE` e retorna uma resposta degradada mas coerente ao cliente. Isso evita perda do pedido: o sistema pode reprocessar pedidos `PENDING` posteriormente.

### Ordem de aplicação das anotações (payment-service)

```
Requisição
    │
    ▼
@CircuitBreaker  ←── se aberto, vai direto ao fallback
    │
    ▼
@TimeLimiter     ←── cancela se demorar mais que 2s
    │
    ▼
@Retry           ←── tenta até 3x em falha transitória
    │
    ▼
PaymentClient.processPayment()
```

### Proteção da camada de banco de dados

Todas as operações de banco de dados passam pelo `ResilientDatabaseService`, que aplica os mesmos padrões (sem `@TimeLimiter`, pois as chamadas são síncronas e não retornam `CompletableFuture`).

| Parâmetro                               | Valor  | Descrição                                          |
|-----------------------------------------|--------|----------------------------------------------------|
| `slidingWindowSize`                     | 5      | Janela de chamadas avaliadas                       |
| `minimumNumberOfCalls`                  | 3      | Mínimo para começar a avaliar                      |
| `failureRateThreshold`                  | 50     | % de falhas para abrir o circuito                  |
| `waitDurationInOpenState`               | 10s    | Tempo em aberto antes de tentar HALF-OPEN          |
| `permittedNumberOfCallsInHalfOpenState` | 2      | Chamadas de teste no estado HALF-OPEN              |
| Retry `maxAttempts`                     | 3      | Tentativas por operação                            |
| Retry `waitDuration`                    | 200ms  | Espera entre tentativas                            |
| Retry `retryExceptions`                 | `TransientDataAccessException` | Cobre timeouts de query, deadlocks e pool esgotado |

`TransientDataAccessException` (e suas subclasses) cobre erros transitórios do JDBC que vale a pena rtentar, como `CannotGetJdbcConnectionException` (pool esgotado), `CannotAcquireLockException` (deadlock) e `QueryTimeoutException`. Erros permanentes como violações de constraint (`NonTransientDataAccessException`) não são retentados.

O circuit breaker do banco é registrado no health indicator e aparece no endpoint `/actuator/circuitbreakers` junto ao `paymentService`.

---

## 6. Fluxos da aplicação

### Fluxo 1 — Sucesso

```
POST /orders
    │
    ▼
OrderApplicationService.createOrder()
    │  salva Order{status=CREATED}
    ▼
ResilientPaymentCallService.callPayment()
    │  CircuitBreaker: CLOSED
    │  TimeLimiter: dentro do prazo
    │  Retry: primeira tentativa OK
    ▼
PaymentClient.processPayment()
    │  POST /payments → 200 OK
    ▼
Order{status=CONFIRMED, paymentStatus=APPROVED}
    │  persiste
    ▼
OrderResponse{status=CONFIRMED, paymentStatus=APPROVED}
```

### Fluxo 2 — Timeout

```
POST /orders
    │
    ▼
ResilientPaymentCallService.callPayment()
    │  POST /payments → demora 5s
    │  TimeLimiter dispara em 2s
    │  cancela CompletableFuture
    ▼
paymentFallback(order, request, TimeoutException)
    │
    ▼
PaymentFallbackHandler.handleFallback()
    │  Order{status=PENDING, paymentStatus=UNAVAILABLE}
    │  persiste
    ▼
OrderResponse{status=PENDING, paymentStatus=UNAVAILABLE}
```

### Fluxo 3 — Retry com recuperação

```
POST /orders
    │
    ▼
ResilientPaymentCallService.callPayment()
    │
    ├─ tentativa 1 → POST /payments → 500  ──▶ PaymentIntegrationException
    │                                           Retry aguarda 500ms
    ├─ tentativa 2 → POST /payments → 200  ──▶ sucesso
    │
    ▼
Order{status=CONFIRMED, paymentStatus=APPROVED}
```

### Fluxo 4 — Circuit Breaker aberto

```
POST /orders (chamadas repetidas com payment-service em ERROR)
    │
    ├─ chamada 1 → falha  ┐
    ├─ chamada 2 → falha  ├─ CircuitBreaker registra falhas
    ├─ chamada 3 → falha  ┘  taxa = 100% > 50% → circuito ABRE
    │
    ├─ chamada 4 → CircuitBreaker OPEN
    │              não chega ao payment-service
    │              vai direto ao fallback
    ▼
paymentFallback(order, request, CallNotPermittedException)
    │
    ▼
OrderResponse{status=PENDING, paymentStatus=UNAVAILABLE}
    (resposta imediata, sem latência da dependência)
```

---

## 7. Camada de persistência

### Entidade `Order`

```
orders
├── id            BIGSERIAL PRIMARY KEY
├── product_id    VARCHAR NOT NULL
├── customer_id   VARCHAR NOT NULL
├── amount        NUMERIC NOT NULL
├── status        VARCHAR NOT NULL   -- OrderStatus
├── payment_status VARCHAR           -- PaymentStatus
├── payment_id    VARCHAR
├── message       VARCHAR(512)
└── created_at    TIMESTAMP NOT NULL
```

O campo `created_at` é preenchido automaticamente pelo callback `@PrePersist` na entidade, sem depender de defaults do banco.

O schema é criado automaticamente pelo Hibernate (`ddl-auto: update` em produção, `create-drop` nos testes).

### `OrderRepository`

Interface `JpaRepository<Order, Long>` sem métodos customizados. Todo acesso ao banco passa pelo `ResilientDatabaseService`, que envolve o repositório com Circuit Breaker e Retry antes de delegar ao Spring Data JPA.

---

## 8. Testes

### Unitários

Testam componentes isolados com Mockito, sem contexto Spring.

| Classe                         | O que testa                                                       |
|--------------------------------|-------------------------------------------------------------------|
| `PaymentSimulationServiceTest` | Lógica dos quatro modos de simulação                              |
| `PaymentFallbackHandlerTest`   | Comportamento do fallback: status persistido e resposta retornada |

### Integração

Sobem o contexto Spring completo com dependências reais.

| Classe                             | Infraestrutura              | O que testa                                                  |
|------------------------------------|-----------------------------|--------------------------------------------------------------|
| `PaymentControllerIntegrationTest` | Nenhuma (MockMvc)           | Endpoints do payment-service para cada modo                  |
| `OrderPersistenceIntegrationTest`  | PostgreSQL (Testcontainers) | Persistência correta da entidade `Order`                     |
| `DatabaseResilienceTest`           | PostgreSQL (Testcontainers) | Circuit breaker do banco abre após falhas `TransientDataAccessException` |

A URL do banco é injetada dinamicamente via `@DynamicPropertySource`, que sobrescreve as propriedades do `application.yml` antes da inicialização do contexto Spring.

`DatabaseResilienceTest` usa `@MockBean OrderRepository` para injetar falhas programaticamente. Os thresholds do Resilience4j são sobrescritos para valores agressivos (`minimumNumberOfCalls=3`, `maxAttempts=1`) para que o circuit breaker abra rapidamente. A asserção principal é `verify(orderRepository, times(3)).save(any())` — prova que a 4ª chamada foi bloqueada pelo proxy AOP sem chegar ao repositório.

### End-to-end

Sobem a aplicação completa em porta aleatória com banco real e dependência externa simulada.

| Classe              | Infraestrutura                         | Cenários                  |
|---------------------|----------------------------------------|---------------------------|
| `OrderEndToEndTest` | PostgreSQL (Testcontainers) + WireMock | 4 cenários de resiliência |

O WireMock substitui o `payment-service` real, permitindo controlar com precisão latência, erros e comportamentos intermitentes. A URL base do payment-service é sobrescrita via `@DynamicPropertySource` para apontar à porta dinâmica do WireMock.

Os parâmetros do Resilience4j também são sobrescritos para valores mais agressivos (ex.: timeout de 1s, retry de 2 tentativas), tornando os cenários rápidos e determinísticos nos testes.

---

## 9. Resiliência do banco de dados

### Motivação

Embora o banco seja uma dependência interna, ele também pode apresentar latência, timeouts de query, esgotamento de pool de conexões ou deadlocks transitórios. Sem proteção, uma falha momentânea no PostgreSQL pode se propagar para todos os endpoints do `order-service`, causando indisponibilidade total.

### Arquitetura da solução

```
OrderApplicationService        ResilientPaymentCallService        PaymentFallbackHandler
        │                                   │                               │
        ▼                                   ▼                               ▼
ResilientDatabaseService  ←─────── Circuit Breaker + Retry ──────────────────
        │
        ▼
  OrderRepository (JPA)
        │
        ▼
    PostgreSQL
```

### Fallback e degradação

Diferente do fallback de pagamento (que salva o pedido como `PENDING`), o fallback de banco de dados não tem valor padrão razoável para retornar. Por isso, ele lança `DatabaseUnavailableException`, que o `GlobalExceptionHandler` mapeia para HTTP 503. O cliente recebe um erro explícito — o que é mais correto do que retornar dados inconsistentes.

### Exceções cobertas pelo Retry

| Subclasse de `TransientDataAccessException`   | Causa                                    |
|-----------------------------------------------|------------------------------------------|
| `CannotGetJdbcConnectionException`            | Pool de conexões esgotado                |
| `CannotAcquireLockException`                  | Deadlock ou timeout de lock              |
| `QueryTimeoutException`                       | Query demorou mais que o timeout JDBC    |

Erros permanentes (violações de constraint, dados inválidos) extendem `NonTransientDataAccessException` e não são cobertos — rtentar não os resolve.
