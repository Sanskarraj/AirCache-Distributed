# Multi-stage build for lightweight distributed cache image
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cache dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Build application fat JAR
COPY src ./src
RUN mvn clean package -DskipTests

# Runtime image
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Create non-root user and data directory
RUN useradd -u 1001 distcache && \
    mkdir -p /app/data && \
    chown -R distcache:distcache /app

USER distcache

COPY --from=build /app/target/distributed-in-memory-cache-1.0.0-SNAPSHOT.jar app.jar

ENV JVM_OPTS="-Xms512m -Xmx2g -XX:+UseG1GC -XX:+AlwaysPreTouch"

EXPOSE 8001 9001

ENTRYPOINT ["sh", "-c", "java $JVM_OPTS -jar app.jar \"$@\"", "--"]
