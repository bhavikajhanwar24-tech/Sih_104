# SentinelVoice Decision Plane — build from repo root: docker compose build backend
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /src/backend

COPY backend/pom.xml .
COPY backend/src ./src
COPY scenarios /src/scenarios
# Fairness JSON is optional — portal shows EVALUATION_NOT_RUN when absent.
RUN mkdir -p /src/ml-engine/benchmarks

RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build /src/backend/target/backend-0.0.1-SNAPSHOT.jar /app/app.jar

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --retries=8 --start-period=90s \
  CMD curl -sf http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
