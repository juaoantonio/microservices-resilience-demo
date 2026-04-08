FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY payment-service/build.gradle ./payment-service/
COPY payment-service/src ./payment-service/src
RUN ./gradlew :payment-service:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/payment-service/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
