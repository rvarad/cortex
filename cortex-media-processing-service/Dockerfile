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

RUN groupadd --system app && useradd --system --gid app app
# Copy the fat jar from the build stage
COPY --from=build --chown=app:app /app/cortex-media-processing-service/target/cortex-media-processing-service-0.0.1-SNAPSHOT.jar app.jar

USER app

# Expose the application port
EXPOSE 8081

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0",  "-jar", "app.jar"]
