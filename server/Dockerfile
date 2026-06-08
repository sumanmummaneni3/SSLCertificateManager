# ─────────────────────────────────────────────────────────────────────────────
# CertGuard Cloud server image — self-contained multi-stage build.
#
# Build context is the REPO ROOT (not server/), so this Dockerfile can reach the
# sibling ui/ and agent/ source trees and bake their artifacts into the server
# classpath. Build it as:
#
#   docker build -f server/Dockerfile -t certguard:local .
#
# server/src/main/resources/{static,agent} are .dockerignore'd on purpose — they
# are regenerated here (ui-builder + agent-builder) rather than copied from the
# context, so a clean checkout produces a complete image.
# ─────────────────────────────────────────────────────────────────────────────

# ─────────────────────────────────────────
# Stage 1: Build the UI bundle (Vite/React)
# ─────────────────────────────────────────
FROM node:20-alpine AS ui-builder

WORKDIR /app

# Install dependencies first (layer-cached separately from source)
COPY ui/package.json ui/package-lock.json ./
RUN npm ci

# Copy source and build
COPY ui/ ./
ENV VITE_DEV_MODE=false
RUN npm run build

# Vite emits hidden source maps to dist/ for upload to an error tracker; they
# must NOT be served from the server's public static dir (they expose source).
RUN find dist -name '*.map' -delete

# ─────────────────────────────────────────
# Shared Maven base (JDK 25) for the agent + server builds
# ─────────────────────────────────────────
FROM eclipse-temurin:25-jdk-alpine AS maven-base

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

# ─────────────────────────────────────────
# Stage 2: Build the agent JAR (baked into the server classpath so
#          AgentBundleService can serve installer bundles offline)
# ─────────────────────────────────────────
FROM maven-base AS agent-builder

WORKDIR /agent

COPY agent/pom.xml .
RUN mvn dependency:go-offline -q

COPY agent/src ./src
RUN mvn clean package -DskipTests -q

# ─────────────────────────────────────────
# Stage 3: Build the server JAR
# ─────────────────────────────────────────
FROM maven-base AS builder

WORKDIR /build

COPY server/pom.xml .
RUN mvn dependency:go-offline -q

COPY server/src ./src

# Bake the freshly built UI + agent into the server classpath resources.
COPY --from=ui-builder    /app/dist                            ./src/main/resources/static/
COPY --from=agent-builder /agent/target/certguard-agent.jar    ./src/main/resources/agent/certguard-agent.jar

RUN mvn clean package -DskipTests -q

# ─────────────────────────────────────────
# Stage 4: Runtime with JRE 25
# ─────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine

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
