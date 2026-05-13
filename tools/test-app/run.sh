#!/usr/bin/env bash
# Launch the plugin test app. Works from any directory — locates the
# repo root via this script's path.
set -e
cd "$(dirname "$0")/../.."

if ! command -v java >/dev/null 2>&1; then
    echo "Java is not on your PATH. Install a JDK (Adoptium / Microsoft /"
    echo "Oracle) from https://adoptium.net/ and try again."
    exit 1
fi

exec java tools/test-app/TestApp.java
