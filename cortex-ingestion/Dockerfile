# Build stage
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# Copy the entire project for multi-module build
COPY . .

# Build only the ingestion module and its dependencies (like cortex-common)
RUN mvn clean package -pl cortex-ingestion -am -DskipTests
RUN java -Djarmode=tools -jar cortex-ingestion/target/cortex-ingestion-0.0.1-SNAPSHOT.jar extract --layers --destination target/extracted

# Run stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy layers separately to maximize Docker caching
COPY --from=build /app/target/extracted/dependencies/ ./
COPY --from=build /app/target/extracted/spring-boot-loader/ ./
COPY --from=build /app/target/extracted/snapshot-dependencies/ ./
COPY --from=build /app/target/extracted/application/ ./

# Expose the application port
EXPOSE 8080

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
