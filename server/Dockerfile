# ─────────────────────────────────────────
# Stage 1: Build with JDK 17
# ─────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /build

ARG MAVEN_VERSION=3.9.8
RUN apk add --no-cache curl && \
    curl -fsSL https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz \
    | tar -xz -C /opt && \
    ln -s /opt/apache-maven-${MAVEN_VERSION} /opt/maven

ENV PATH="/opt/maven/bin:${PATH}" \
    MAVEN_HOME="/opt/maven"

# Cache Maven dependencies separately from source
COPY pom.xml .
RUN mvn dependency:go-offline -q

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
