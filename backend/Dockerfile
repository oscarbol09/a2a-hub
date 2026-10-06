FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src src
RUN mvn package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Upgrade base OS packages to resolve system-level CVEs and create non-privileged user
RUN apk upgrade --no-cache && \
    addgroup -S a2agroup && \
    adduser -S a2a -G a2agroup

COPY --from=build --chown=a2a:a2agroup /app/target/*.jar app.jar

USER a2a:a2agroup
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD wget -q --spider http://localhost:8080/v3/api-docs || exit 1

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
