FROM gradle:8.13-jdk21 AS builder
WORKDIR /octi-server

# Copy Gradle wrapper files first for better caching
COPY gradlew ./
COPY gradlew.bat ./
COPY gradle/ ./gradle/

# Convert line endings and make gradlew executable (fixes Windows CRLF issues)
RUN sed -i 's/\r$//' ./gradlew && chmod +x ./gradlew

# Copy build files for dependency resolution
COPY build.gradle.kts ./
COPY settings.gradle.kts ./
COPY gradle.properties ./

# Download dependencies (cached layer)
RUN ./gradlew dependencies --no-daemon

# Copy source code
COPY src/ ./src/

# Build the application
RUN ./gradlew clean installDist --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /octi-server

# Create non-root user for security (let system assign UID)
RUN useradd -r -s /bin/bash -m octi-user

# Copy built application
COPY --from=builder /octi-server/build/install/octi-server/ .

# Copy entrypoint script
COPY docker-entrypoint.sh .

# Create data directories and set permissions
RUN mkdir -p /etc/octi-server /etc/octi-sync-server && \
    sed -i 's/\r$//' ./docker-entrypoint.sh && \
    chown -R octi-user:octi-user /octi-server /etc/octi-server /etc/octi-sync-server && \
    chmod +x ./bin/octi-server && \
    chmod +x ./docker-entrypoint.sh

# Switch to non-root user
USER octi-user

# Expose the application port
EXPOSE 8080

# Declare volume for data persistence
VOLUME ["/etc/octi-server"]

# Use the entrypoint script
ENTRYPOINT ["./docker-entrypoint.sh"]
