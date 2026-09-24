# =============================================================================
# SentinelVoice Backend — Root Dockerfile
# Optimized for Render Web Services & Container Deployments
# =============================================================================

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy Maven POM and source files
COPY backend/pom.xml .
COPY backend/src ./src

# Build production JAR without test overhead
RUN mvn -B clean package -DskipTests

# Runtime Stage
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build /build/target/*.jar /app/app.jar

# Render dynamic port support
ENV PORT=8081
EXPOSE 8080 8081 10000

HEALTHCHECK --interval=15s --timeout=5s --retries=8 --start-period=60s \
  CMD curl -sf http://127.0.0.1:${PORT:-8081}/actuator/health || exit 1

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
