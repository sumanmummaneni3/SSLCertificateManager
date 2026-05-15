# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk-alpine AS builder

WORKDIR /build

# Cache the dependency resolution layer before copying source
COPY pom.xml ./
RUN apk add --no-cache maven && \
    mvn dependency:go-offline -q

COPY src/ ./src/

RUN mvn package -DskipTests -q
# maven-shade-plugin produces target/certguard-agent.jar (finalName in pom.xml)

# ── Stage 2: Runtime ───────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine

# Non-root user — principle of least privilege
RUN addgroup -S certguard && adduser -S certguard -G certguard

WORKDIR /app

COPY --from=builder /build/target/certguard-agent.jar /app/certguard-agent.jar

USER certguard

# Agent is outbound-only — no inbound port exposure needed
ENTRYPOINT ["java", "-jar", "/app/certguard-agent.jar"]
