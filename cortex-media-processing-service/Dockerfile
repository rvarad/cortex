# Build stage
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# Copy the entire project for multi-module build
COPY . .

# Build only the media processing module and its dependencies (like cortex-common)
RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -pl cortex-media-processing-service -am -DskipTests
RUN java -Djarmode=tools -jar cortex-media-processing-service/target/cortex-media-processing-service-0.0.1-SNAPSHOT.jar extract --layers --destination target/extracted

# FFmpeg stage
FROM mwader/static-ffmpeg:7.1 AS ffmpeg

# Run stage
FROM eclipse-temurin:21-jre-alpine
COPY --from=ffmpeg /ffmpeg /usr/local/bin/
COPY --from=ffmpeg /ffprobe /usr/local/bin/
WORKDIR /app

# Copy layered jar components to maximize Docker caching
COPY --from=build /app/target/extracted/dependencies/ ./
COPY --from=build /app/target/extracted/spring-boot-loader/ ./
COPY --from=build /app/target/extracted/snapshot-dependencies/ ./
COPY --from=build /app/target/extracted/application/ ./

# Expose the application port
EXPOSE 8081

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
