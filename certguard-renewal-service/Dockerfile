# ─────────────────────────────────────────
# Stage 1: Build
# ─────────────────────────────────────────
FROM eclipse-temurin:25-jdk-alpine AS builder

WORKDIR /build

ARG MAVEN_VERSION=3.9.8
RUN apk add --no-cache curl && \
    curl -fsSL --retry 3 --retry-delay 2 \
        -o /tmp/maven.tar.gz \
        https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz && \
    tar -xzf /tmp/maven.tar.gz -C /opt && \
    ln -s /opt/apache-maven-${MAVEN_VERSION} /opt/maven && \
    rm /tmp/maven.tar.gz && \
    apk del curl

ENV PATH="/opt/maven/bin:${PATH}" MAVEN_HOME="/opt/maven"

COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn clean package -DskipTests -q

# ─────────────────────────────────────────
# Stage 2: Runtime (JRE only)
# ─────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine

WORKDIR /opt/certguard-renewal

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=builder /build/target/certguard-renewal-service-*.jar app.jar

EXPOSE 8444

ENTRYPOINT ["java", "-jar", "app.jar"]
