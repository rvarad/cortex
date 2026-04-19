# Build stage
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# Copy the entire project for multi-module build
COPY . .

# Build only the gateway module
RUN mvn package -pl cortex-gateway -am -DskipTests

# Run stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Copy the fat jar from the build stage
COPY --from=build /app/cortex-gateway/target/cortex-gateway-0.0.1-SNAPSHOT.jar app.jar

# Expose the application port
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]