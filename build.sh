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

echo "=== [1/2] Building UI ==="
cd "$ROOT/ui"
npm install --silent
npm run build
echo "UI build complete: $(ls dist/assets | wc -l) assets generated"

# NOTE: UI staging into server/src/main/resources/static/ is no longer done
# here. server/Dockerfile now has a multi-stage build (ui-builder stage) that
# compiles the UI and copies dist/ into the server classpath before mvn package.
# The npm build above is kept so developers can iterate on the UI locally.

echo ""
echo "=== [2/2] Building agent ==="
cd "$ROOT/agent"
JAVA_HOME="$JAVA17_HOME" mvn clean package -q
AGENT_JAR="$ROOT/agent/target/certguard-agent.jar"
echo "Agent build complete: $AGENT_JAR"

# NOTE: Agent JAR staging into server/src/main/resources/agent/ is also handled
# by server/Dockerfile (agent-builder stage). Local staging is only needed if
# running `mvn spring-boot:run` from server/ directly without Docker; uncomment
# the block below in that case.
#
#   AGENT_RESOURCE_DIR="$ROOT/server/src/main/resources/agent"
#   mkdir -p "$AGENT_RESOURCE_DIR"
#   cp "$AGENT_JAR" "$AGENT_RESOURCE_DIR/certguard-agent.jar"
#   echo "Copied agent JAR → server/src/main/resources/agent/certguard-agent.jar"

echo ""
echo "=============================="
echo " Local artifacts built"
echo "=============================="
echo " UI dist    : $ROOT/ui/dist"
echo " Agent JAR  : $AGENT_JAR"
echo ""
echo " To build the Docker image (from repo root):"
echo "   docker build -f server/Dockerfile -t certguard:local ."
echo ""
echo " To run the server JAR directly (requires manual staging — see comments above):"
echo "   cd server && JAVA_HOME=\"$JAVA17_HOME\" mvn clean package -DskipTests && mvn spring-boot:run"
