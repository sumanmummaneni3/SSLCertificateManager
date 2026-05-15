## ── Stage 1: build ───────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk-alpine AS builder

WORKDIR /build

ARG MAVEN_VERSION=3.9.8
ARG MAVEN_SHA512=7d171def9b85846bf757a2cec94b7529371068a0670df14682447224e57983528e97a6d1b850327e4ca02b139abaab7fcb93c4315119e6f0ffb3f0cbc0d0b9a2

RUN set -eux; \
    apk add --no-cache curl; \
    curl -fsSL --retry 3 --retry-delay 2 \
        -o /tmp/maven.tar.gz \
        https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz; \
    echo "${MAVEN_SHA512}  /tmp/maven.tar.gz" | sha512sum -c -; \
    tar -xzf /tmp/maven.tar.gz -C /opt; \
    ln -s /opt/apache-maven-${MAVEN_VERSION} /opt/maven; \
    rm /tmp/maven.tar.gz; \
    apk del curl

ENV PATH="/opt/maven/bin:${PATH}" \
    MAVEN_HOME="/opt/maven"

# Resolve dependencies first for better layer caching
COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn clean package -DskipTests -q

## ── Stage 2: runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=builder /build/target/certguard-auth-service-*.jar app.jar

RUN chown appuser:appgroup app.jar
USER appuser

EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8090/actuator/health | grep -q UP || exit 1

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
