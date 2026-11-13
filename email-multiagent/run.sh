#!/usr/bin/env bash
# Build assistant-core and the reactor, then start the web server on http://localhost:8080
# (the Planner assistant is at /assistant.html). Needs a JDK 21+ and Maven.
set -euo pipefail

cd "$(dirname "$0")"

# The system `java` may be old: look for a JDK 21+.
java_ok() { [[ -x "$1/bin/java" ]] && [[ "$("$1/bin/java" -version 2>&1 | sed -n '1s/[^"]*"\([0-9]*\).*/\1/p')" -ge 21 ]]; }
for home in "${JAVA_HOME:-}" /opt/homebrew/opt/openjdk /opt/homebrew/opt/openjdk@21 \
            /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home /usr/lib/jvm/*; do
  if [[ -n "$home" ]] && java_ok "$home"; then export JAVA_HOME="$home"; break; fi
  if [[ -n "$home" ]] && java_ok "$home/libexec/openjdk.jdk/Contents/Home"; then
    export JAVA_HOME="$home/libexec/openjdk.jdk/Contents/Home"; break
  fi
done
[[ -n "${JAVA_HOME:-}" ]] || { echo "JDK 21+ not found (brew install openjdk)" >&2; exit 1; }
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$PATH"

# email-app embeds assistant-core, so install it first.
(cd ../assistant-core && mvn -q -DskipTests install)
mvn -q -DskipTests install
exec mvn -q -pl email-app exec:java
