#!/usr/bin/env bash
# Double-click / `./run.sh` to launch the Vocal Monitor DAW.
# Finds a JDK on $JAVA_HOME or PATH and keeps the terminal open if
# something goes wrong so the Gradle / Kotlin / Compose error is
# readable instead of a closed-window mystery.
#
# First run downloads Gradle 8.9 + Kotlin 2.0.20 + Compose Desktop
# 1.7.0 (~150 MB) into ~/.gradle.  Subsequent runs are instant.

set -e
cd "$(dirname "$0")"

# Try to find a usable JDK.
if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
    if command -v java >/dev/null 2>&1; then
        # Resolve the JDK home from `java` on PATH.
        JAVA_BIN=$(command -v java)
        JAVA_REAL=$(readlink -f "$JAVA_BIN" 2>/dev/null || echo "$JAVA_BIN")
        export JAVA_HOME=$(dirname "$(dirname "$JAVA_REAL")")
    fi
fi

if [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo
    echo "Could not find a JDK.  Install one (Adoptium / Microsoft" >&2
    echo "OpenJDK / Oracle) and either add it to PATH or set" >&2
    echo "JAVA_HOME to point at the install folder." >&2
    echo
    read -n 1 -s -r -p "Press any key to close..."
    exit 1
fi

echo "Using JDK: $JAVA_HOME"
echo

if ! ./gradlew --console=plain run; then
    rc=$?
    echo
    echo "Gradle exited with error $rc."
    echo "Scroll up to see what went wrong."
    echo
    read -n 1 -s -r -p "Press any key to close..."
    exit $rc
fi
