#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
if ! command -v java >/dev/null 2>&1; then
  echo "Java 21 не найдена. Установи JDK 21 и повтори запуск."
  exit 1
fi
exec java -jar PluginReconstructorUltra-0.2.0.jar gui
