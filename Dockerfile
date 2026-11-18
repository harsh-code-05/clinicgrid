# syntax=docker/dockerfile:1
# =============================================================================
# Stage 1: Build application and extract Spring Boot layers
# =============================================================================
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /workspace

# Install maven for deterministic, reproducible build
RUN apt-get update && apt-get install -y --no-install-recommends maven && rm -rf /var/lib/apt/lists/*

# Copy POM and resolve dependencies for efficient layer caching
COPY pom.xml ./
RUN mvn dependency:go-offline -B || true

# Copy source and database migration resources
COPY src/ ./src/
COPY database/ ./database/

# Build production JAR (skipping test suite inside container build)
RUN mvn clean package -DskipTests -B

# Extract Spring Boot layers using the built-in tools mode (including launcher classes)
RUN java -Djarmode=tools -jar target/hospital-management-system-0.0.1-SNAPSHOT.jar extract --launcher --layers --destination /workspace/extracted

# =============================================================================
# Stage 2: Hardened, production-ready runtime (Render / Linux container)
# =============================================================================
FROM eclipse-temurin:21-jre-jammy AS runner

# Install minimal network probe utilities for healthchecks
RUN apt-get update && apt-get install -y --no-install-recommends wget curl && rm -rf /var/lib/apt/lists/*

# Create dedicated non-root application group and user (UID/GID 10001)
RUN groupadd -g 10001 hmsgroup \
    && useradd -u 10001 -g hmsgroup -m -s /bin/bash hmsuser

WORKDIR /app

# Copy extracted layers in order of modification frequency for optimal Docker layer caching
COPY --chown=hmsuser:hmsgroup --from=builder /workspace/extracted/dependencies/ ./
COPY --chown=hmsuser:hmsgroup --from=builder /workspace/extracted/spring-boot-loader/ ./
COPY --chown=hmsuser:hmsgroup --from=builder /workspace/extracted/snapshot-dependencies/ ./
COPY --chown=hmsuser:hmsgroup --from=builder /workspace/extracted/application/ ./

# Container runtime & JVM memory tuning for low-memory environments (Render 512MB RAM tier)
# Allocates 65% max RAM to heap (~332MB), caps metaspace, thread stacks, and JIT compilers
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=65.0 \
               -XX:InitialRAMPercentage=40.0 \
               -XX:MaxMetaspaceSize=128m \
               -XX:CompressedClassSpaceSize=32m \
               -XX:ReservedCodeCacheSize=64m \
               -Xss256k \
               -XX:CICompilerCount=2 \
               -Djava.security.egd=file:/dev/./urandom \
               -Dfile.encoding=UTF-8"

# Dynamic port binding with 8080 fallback (Render injects $PORT at runtime)
ENV PORT=8080
EXPOSE 8080

# Configure graceful shutdown timeouts
ENV SERVER_SHUTDOWN=graceful
ENV SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE=20s

# Run as non-root user
USER hmsuser:hmsgroup

# Container healthcheck probe
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -f http://127.0.0.1:${PORT}/api/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher --server.port=${PORT}"]
