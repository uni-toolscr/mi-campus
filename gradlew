#!/bin/sh
set -e
if [ -n "$GRADLE_HOME" ] && [ -x "$GRADLE_HOME/bin/gradle" ]; then exec "$GRADLE_HOME/bin/gradle" "$@"; fi
if command -v gradle >/dev/null 2>&1; then exec gradle "$@"; fi
DIST=$(find "$HOME/.gradle/wrapper/dists" -path '*/gradle-9.5.0/bin/gradle' -type f 2>/dev/null | head -n 1)
if [ -n "$DIST" ]; then exec "$DIST" "$@"; fi
exec java -classpath "$(dirname "$0")/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
