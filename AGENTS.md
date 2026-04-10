# Repository Guidelines

## Project Structure & Module Organization
This is a Gradle multi-module Spring Boot demo for resilience patterns. The root contains shared Gradle config (`build.gradle`, `settings.gradle`), local orchestration (`docker-compose.yml`), request samples (`requests.http`), and reference docs (`README.md`, `GUIDE.md`).

`order-service/` is the main service. Java sources live under `src/main/java/com/demo/order`, organized by `controller`, `service`, `client`, `domain`, `dto`, `exception`, and `config`. `payment-service/` is the external payment simulator, with code under `src/main/java/com/demo/payment`. Tests live in each module under `src/test/java`; runtime config is in `src/main/resources/application.yml`.

## Build, Test, and Development Commands
Use the Gradle wrapper and run commands from the repository root:

- `./gradlew test`: run all tests; Docker is required for Testcontainers-based suites.
- `./gradlew :order-service:test`: run order-service tests only.
- `./gradlew :payment-service:test`: run payment-service tests only.
- `./gradlew :order-service:bootRun`: start the order service on port `8080`.
- `./gradlew :payment-service:bootRun`: start the payment simulator on port `8081`.
- `docker compose up --build`: start PostgreSQL plus both services for the full demo.

## Coding Style & Naming Conventions
Follow existing Java conventions: 4-space indentation, one public class per file, `PascalCase` for classes and enums, `camelCase` for methods and fields. Keep package boundaries aligned with the current module layout. Use descriptive suffixes such as `*Controller`, `*Service`, `*Handler`, `*Request`, and `*Response`. Prefer small Spring components with focused responsibilities; resilience logic belongs in dedicated service beans, not controllers.

## Testing Guidelines
The project uses JUnit 5, Spring Boot Test, AssertJ, Testcontainers, Awaitility, and WireMock. Name tests with the existing patterns: unit tests as `*Test`, integration tests as `*IntegrationTest`, and scenario flows such as `OrderEndToEndTest`. Keep test method names descriptive, and match the current style of `@DisplayName` for user-facing scenarios. Cover normal flow, timeout/error paths, and fallback behavior when changing resilience logic.

## Commit & Pull Request Guidelines
Match the repository history and use Conventional Commit prefixes such as `feat:`, `fix:`, `docs:`, `refactor:`, and `style:`. Keep commits scoped to one concern. PRs should summarize the changed module, list validation performed (`./gradlew test`, targeted module tests, or `docker compose up`), and include example requests or screenshots when API behavior or observability output changes.

## Configuration & Environment Notes
Use Java 21. Docker is required for local PostgreSQL and for Testcontainers-backed tests. Default service ports are `8080` for `order-service`, `8081` for `payment-service`, and `5432` for PostgreSQL.
