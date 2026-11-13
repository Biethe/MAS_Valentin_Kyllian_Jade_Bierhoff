#!/usr/bin/env bash
# One launcher for every way of using the mail assistant.
#
#   ./run.sh cli   [flags]        terminal only (default)
#   ./run.sh web   [flags]        browser chat on http://localhost:8090 (+ JSON API)
#   ./run.sh app                  full web app (IMAP inbox, router, summaries) on http://localhost:8080,
#                                 with the Planner assistant at /assistant.html
#   ./run.sh bench [args]         the benchmark (lint | check | run | diff | compare | show)
#   ./run.sh bench-run [--only a,b] [--judge-model M] [--mock]   run the real assistant through the benchmark's scenarios (all 20, or the listed ids)
#   ./run.sh test                 every test of every module
#
# Flags for cli / web / bench-run: --mock (no model, scripted answers), --model NAME,
# --ollama-url URL, --mcp-mailbox (use MAILBOX_CMD), --port N.
set -euo pipefail
START_DIR="$(pwd)"
cd "$(dirname "$0")"
ROOT="$(pwd)"

# The system `java` may be old (macOS often ships 8): find a JDK 21+.
java_ok() { [[ -x "$1/bin/java" ]] && [[ "$("$1/bin/java" -version 2>&1 | sed -n '1s/[^"]*"\([0-9]*\).*/\1/p')" -ge 21 ]]; }
find_jdk() {
  local h
  for h in "${JAVA_HOME:-}" /opt/homebrew/opt/openjdk /opt/homebrew/opt/openjdk@21 /opt/homebrew/opt/openjdk@27 \
           /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
           /usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home /usr/lib/jvm/* \
           /Library/Java/JavaVirtualMachines/*/Contents/Home; do
    [[ -n "$h" ]] && java_ok "$h" && { echo "$h"; return 0; }
    [[ -n "$h" ]] && java_ok "$h/libexec/openjdk.jdk/Contents/Home" && { echo "$h/libexec/openjdk.jdk/Contents/Home"; return 0; }
  done
  java_ok "$(dirname "$(dirname "$(command -v java 2>/dev/null || echo /nonexistent/x)")")" \
    && { dirname "$(dirname "$(command -v java)")"; return 0; }
  return 1
}
JAVA_HOME="$(find_jdk)" || { echo "JDK 21+ not found. Install it: brew install openjdk" >&2; exit 1; }
export JAVA_HOME PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$PATH"

build() { [[ -f assistant-core/target/lib/.ok || -d assistant-core/target/lib ]] || mvn -q -DskipTests package; }
core_cp() { echo "assistant-core/target/classes:assistant-core/target/lib/*"; }
fresh_build() { mvn -q -DskipTests package; }

mode="${1:-cli}"; shift || true
case "$mode" in
  cli)  build; exec java -cp "$(core_cp)" com.assistant.mail.Main "$@" cli ;;
  web)  build; exec java -cp "$(core_cp)" com.assistant.mail.Main "$@" web ;;
  app)  mvn -q -DskipTests install && cd email-multiagent && exec mvn -q -pl email-app exec:java ;;
  bench) mvn -q -DskipTests package -pl benchmark && cd benchmark && exec ./bench "$@" ;;
  bench-run)
    fresh_build
    only=""; rest=()
    while [[ $# -gt 0 ]]; do
      if [[ "$1" == "--only" ]]; then only="$2"; shift 2;
      elif [[ "$1" == "--judge-model" ]]; then export BENCH_JUDGE_MODEL="$2"; shift 2;
      else rest+=("$1"); shift; fi
    done
    cd benchmark
    exec ./bench run ${only:+--only "$only"} --system "'$ROOT/run.sh' bench-system ${rest[*]:-}" ;;
  bench-system)
    # called by ./bench from benchmark/: its MAILBOX_CMD uses paths relative to that folder
    build
    export CONTEXT_DIR="${CONTEXT_DIR:-$ROOT/context}"
    cd "$START_DIR"
    exec java -cp "$ROOT/assistant-core/target/classes:$ROOT/assistant-core/target/lib/*" com.assistant.mail.bench.BenchSystem "$@" ;;
  test) exec mvn test ;;
  *) sed -n '2,14p' "$0"; exit 2 ;;
esac
