# ─────────────────────────────────────────
# Stage 1: Build with JDK 17
# ─────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /build

# Cache Maven dependencies separately from source
COPY pom.xml .
RUN apk add --no-cache maven && \
    mvn dependency:go-offline -q

COPY src ./src
RUN mvn clean package -DskipTests -q

# ─────────────────────────────────────────
# Stage 2: Runtime with JRE 17
# ─────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

RUN addgroup -S certguard && adduser -S certguard -G certguard

COPY --from=builder /build/target/certguard-cloud-*.jar app.jar

RUN mkdir -p /opt/certguard/keystores && \
    chown -R certguard:certguard /opt/certguard

USER certguard

EXPOSE 8443

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -q --no-check-certificate -O- https://localhost:8443/actuator/health || exit 1

ENTRYPOINT ["java", \
    "-jar", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "app.jar"]
