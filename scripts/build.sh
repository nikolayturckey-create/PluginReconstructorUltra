#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
rm -rf build/classes dist
mkdir -p build/classes dist
find src/main/java -name '*.java' -print0 | sort -z | xargs -0 javac --release 21 -encoding UTF-8 -d build/classes
jar --create --file dist/PluginReconstructorUltra-0.2.0.jar \
    --main-class dev.nik.reconstructor.Main \
    -C build/classes .
cp dist/PluginReconstructorUltra-0.2.0.jar PluginReconstructorUltra-0.2.0.jar
echo "Built: $ROOT/PluginReconstructorUltra-0.2.0.jar"
