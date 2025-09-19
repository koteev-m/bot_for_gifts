# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew ./gradlew
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts ./
COPY config ./config
COPY src ./src
RUN chmod +x gradlew
RUN ./gradlew --no-daemon clean installDist

FROM eclipse-temurin:21-jre AS run
WORKDIR /opt/app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/build/install/gifts-bot /opt/app
EXPOSE 8080
ENTRYPOINT ["/opt/app/bin/gifts-bot"]
