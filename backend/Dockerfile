# Jammy ofrece Java 17 nativo para AMD64 y ARM64 (Mac con Apple Silicon).
# No usar --platform=linux/amd64: en Apple Silicon eso emula x86_64 en lugar de ARM nativo.
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -DskipTests dependency:go-offline
COPY src src
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:17-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app \
    && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build --chown=app:app /app/target/rentas-*.jar app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=10 CMD wget -q -O - http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
