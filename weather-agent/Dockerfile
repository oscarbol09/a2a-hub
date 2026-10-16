# Stage 1: Build stage
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

# Dependency caching
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Source compilation and packaging
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Hardened Alpine runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Upgrade base OS packages to resolve system CVEs and create non-root user spring (UID/GID 10001)
RUN apk upgrade --no-cache && \
    addgroup -g 10001 -S spring && \
    adduser -u 10001 -S spring -G spring

# Copy packaged jar from build stage
COPY --from=build --chown=spring:spring /app/target/weather-agent-*.jar app.jar

USER 10001:10001
EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD wget -q --spider http://localhost:8081/.well-known/agent-card.json || exit 1

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
