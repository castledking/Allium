#!/usr/bin/env bash
# Test-only javac build; never invokes the repository Gradle build.
set -euo pipefail
cd "$(dirname "$0")"
: "${LANE_TEMPLATE:=/tmp/allium-s05-20261105/provider-gate/lane}"
: "${ALLIUM_JAR:=$LANE_TEMPLATE/plugins/Allium.jar}"
: "${JAVA_HOME:=/usr/lib/jvm/java-25-openjdk}"
: "${OUTPUT:=/tmp/allium-s05-20261106/ce-triggers/fixture}"
: "${SPIGOT_API:=/tmp/allium-lane-spigot-1.20/bundler/libraries/spigot-api-1.20.1-R0.1-SNAPSHOT.jar}"
API="$SPIGOT_API"
GSON=$(find "$LANE_TEMPLATE/libraries/com/google/code/gson" -name '*.jar' | head -1)
GUAVA=$(find "$LANE_TEMPLATE/libraries/com/google/guava/guava" -name '*.jar' | head -1)
mkdir -p "$OUTPUT/classes"
"$JAVA_HOME/bin/javac" --release 17 -cp "$API:$GSON:$GUAVA:$ALLIUM_JAR" -d "$OUTPUT/classes" plugin/trigger/CETriggerGate.java
cp plugin/plugin.yml "$OUTPUT/classes/"
"$JAVA_HOME/bin/jar" cf "$OUTPUT/CETriggerGate.jar" -C "$OUTPUT/classes" .
sha256sum "$OUTPUT/CETriggerGate.jar"
