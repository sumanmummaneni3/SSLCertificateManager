# ─────────────────────────────────────────
# Runtime only — JAR is built on the host
# before running docker compose build
# ─────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Add non-root user for security
RUN addgroup -S certguard && adduser -S certguard -G certguard

# Copy pre-built JAR (built on host with: mvn clean package -DskipTests)
COPY target/certguard-cloud-*.jar app.jar

# Create keystore directory
RUN mkdir -p /opt/certguard/keystores && \
    chown -R certguard:certguard /opt/certguard

USER certguard

EXPOSE 8443

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -q --no-check-certificate -O- https://localhost:8443/actuator/health || exit 1

ENTRYPOINT ["java", \
    "-jar", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "app.jar"]
