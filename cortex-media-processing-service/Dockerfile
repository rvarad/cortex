# Build stage
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# Copy the entire project for multi-module build
COPY . .

# Build only the media processing module and its dependencies (like cortex-common)
RUN mvn install -pl cortex-common -DskipTests && \
    mvn package -pl cortex-media-processing-service -am -DskipTests

# FFmpeg stage
FROM mwader/static-ffmpeg:7.1 AS ffmpeg

# Run stage
FROM eclipse-temurin:21-jre-jammy
COPY --from=ffmpeg /ffmpeg /usr/local/bin/
COPY --from=ffmpeg /ffprobe /usr/local/bin/
WORKDIR /app

# Copy the fat jar from the build stage
COPY --from=build /app/cortex-media-processing-service/target/cortex-media-processing-service-0.0.1-SNAPSHOT.jar app.jar

# Expose the application port
EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
