FROM gradle:8.14-jdk17 AS build
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src
RUN gradle installDist --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/install/vendaflow-sqlite/ ./
EXPOSE 8080
CMD ["./bin/vendaflow-sqlite"]
