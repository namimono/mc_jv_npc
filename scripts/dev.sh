#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -x /opt/homebrew/opt/openjdk@21/bin/java ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21
elif [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  :
elif command -v /usr/libexec/java_home >/dev/null 2>&1; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi
exec ./gradlew "$@"
