FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src/main src/main
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --gid 10001 foya && useradd --uid 10001 --gid foya --no-create-home foya \
    && mkdir -p /app/data && chown -R foya:foya /app
COPY --from=build --chown=foya:foya /build/target/foya-worker-1.0.0.jar /app/foya-worker.jar
USER foya
ENV FOYA_BIND=0.0.0.0 PORT=8080 FOYA_DATA_DIR=/app/data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=8s --start-period=15s --retries=3 CMD ["java", "-jar", "/app/foya-worker.jar", "--healthcheck"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/foya-worker.jar"]
