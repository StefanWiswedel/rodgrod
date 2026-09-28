#!/usr/bin/env bash
# Milestone 0 scoring experiment. Usage: tools/scoring-experiment/run.sh <recordings-folder> [--out dir] [--bands 80,60]
# Needs Java 17+ (Gradle is downloaded automatically by the wrapper).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ARGS=()
for a in "$@"; do
  # Make relative paths absolute because Gradle runs from the repo root.
  if [[ "$a" != -* && -e "$a" ]]; then ARGS+=("$(cd "$(dirname "$a")" && pwd)/$(basename "$a")"); else ARGS+=("$a"); fi
done
cd "$ROOT"
exec ./gradlew -q --console=plain :scoring-experiment:run --args="${ARGS[*]:-}"
