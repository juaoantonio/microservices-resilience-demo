FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY order-service/build.gradle ./order-service/
COPY order-service/src ./order-service/src
RUN ./gradlew :order-service:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/order-service/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
