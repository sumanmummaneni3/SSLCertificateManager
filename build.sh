#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
JAVA17_HOME="${JAVA17_HOME:-/usr/lib/jvm/java-1.17.0-openjdk-amd64}"

# Verify Java 17 is available
if [ ! -d "$JAVA17_HOME" ]; then
  echo "ERROR: Java 17 not found at $JAVA17_HOME"
  echo "       Set JAVA17_HOME to point to a Java 17 installation."
  exit 1
fi

echo "=== [1/4] Building UI ==="
cd "$ROOT/ui"
npm install --silent
npm run build
echo "UI build complete: $(ls dist/assets | wc -l) assets generated"

echo ""
echo "=== [2/4] Staging UI into server static resources ==="
STATIC_DIR="$ROOT/server/src/main/resources/static"
rm -rf "$STATIC_DIR"
mkdir -p "$STATIC_DIR"
cp -r dist/. "$STATIC_DIR/"
echo "Copied UI dist → server/src/main/resources/static/"

echo ""
echo "=== [3/4] Building agent ==="
cd "$ROOT/agent"
JAVA_HOME="$JAVA17_HOME" mvn clean package -q
AGENT_JAR="$(ls "$ROOT/agent/target/certguard-agent.jar")"
echo "Agent build complete: $AGENT_JAR"

echo ""
echo "=== [4/4] Staging agent JAR into server resources ==="
AGENT_RESOURCE_DIR="$ROOT/server/src/main/resources/agent"
mkdir -p "$AGENT_RESOURCE_DIR"
cp "$AGENT_JAR" "$AGENT_RESOURCE_DIR/certguard-agent.jar"
echo "Copied agent JAR → server/src/main/resources/agent/certguard-agent.jar"

echo ""
echo "=== [5/4] Building server ==="
cd "$ROOT/server"
JAVA_HOME="$JAVA17_HOME" mvn clean package -DskipTests -q
SERVER_JAR="$(ls "$ROOT/server/target/certguard-cloud-"*.jar | grep -v sources | head -1)"
echo "Server build complete: $SERVER_JAR"

echo ""
echo "=============================="
echo " Production build successful"
echo "=============================="
echo " Server JAR : $SERVER_JAR"
echo " Run with   : java -jar $SERVER_JAR"
echo " Or         : cd server && docker-compose up -d"
