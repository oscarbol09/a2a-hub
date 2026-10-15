#!/bin/sh
# ----------------------------------------------------------------------------
# Apache Maven Wrapper startup script for POSIX systems
# ----------------------------------------------------------------------------

set -e
BASE_DIR=$(cd "$(dirname "$0")" && pwd)

if command -v mvn >/dev/null 2>&1; then
    exec mvn "$@"
fi

if [ -f "$BASE_DIR/.mvn/wrapper/maven-wrapper.jar" ]; then
    exec java -jar "$BASE_DIR/.mvn/wrapper/maven-wrapper.jar" "$@"
fi

echo "Error: Could not find mvn or maven-wrapper.jar. Please install Maven or run within a container." >&2
exit 1
