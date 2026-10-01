# Multi-stage build for SentinelVoice Backend (Java 21 / Maven 3.9)
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Copy Maven descriptor and source
COPY pom.xml .
COPY src ./src

# Build JAR skipping unit tests for fast cloud deployment build
RUN mvn -B clean package -DskipTests

# Runtime Stage
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build /build/target/*.jar /app/app.jar

# Render dynamically binds port using $PORT
ENV PORT=8081
EXPOSE 8080 8081 10000

HEALTHCHECK --interval=15s --timeout=5s --retries=8 --start-period=60s \
  CMD curl -sf http://127.0.0.1:${PORT:-8081}/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:+ExitOnOutOfMemoryError", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
