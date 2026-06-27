# Build stage
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# Copy the entire project for multi-module build
COPY . .

# Build only the ingestion module and its dependencies (like cortex-common)
RUN mvn install -pl cortex-common -DskipTests && \
    mvn package -pl cortex-rag-orchestration -am -DskipTests

# Run stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app app
# Copy the fat jar from the build stage
COPY --from=build --chown=app:app /app/cortex-rag-orchestration/target/cortex-rag-orchestration-0.0.1-SNAPSHOT.jar app.jar

USER app

# Expose the application port
EXPOSE 8083

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
