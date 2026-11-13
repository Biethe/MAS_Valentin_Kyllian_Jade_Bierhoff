#!/usr/bin/env bash
# Build the reactor and start the web server on http://localhost:8080 (Planner assistant at /assistant.html).
# Prefer ../run.sh app, which also finds a JDK 21+.
set -euo pipefail

cd "$(dirname "$0")/.."
exec ./run.sh app
