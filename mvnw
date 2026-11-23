#!/bin/sh
# Maven Wrapper script (only-script mode — no jar required)
# Generated for pulseOps
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MAVEN_WRAPPER_PROPERTIES="$SCRIPT_DIR/.mvn/wrapper/maven-wrapper.properties"

# Read distributionUrl from properties file
DISTRIBUTION_URL=$(grep "^distributionUrl=" "$MAVEN_WRAPPER_PROPERTIES" | cut -d'=' -f2-)

# Determine local cache dir
MAVEN_USER_HOME="${MAVEN_USER_HOME:-$HOME/.m2/wrapper}"
DISTRIBUTION_ID=$(echo "$DISTRIBUTION_URL" | sed 's|.*/||' | sed 's|\.zip||' | sed 's|\.tar\.gz||')
MAVEN_HOME="$MAVEN_USER_HOME/dists/$DISTRIBUTION_ID"

if [ ! -d "$MAVEN_HOME" ]; then
    mkdir -p "$MAVEN_HOME"
    echo "Downloading Maven from $DISTRIBUTION_URL"
    TMP_ZIP="$MAVEN_HOME/dist.zip"
    if command -v curl > /dev/null 2>&1; then
        curl -fsSL -o "$TMP_ZIP" "$DISTRIBUTION_URL"
    elif command -v wget > /dev/null 2>&1; then
        wget -q -O "$TMP_ZIP" "$DISTRIBUTION_URL"
    else
        echo "ERROR: neither curl nor wget found" >&2; exit 1
    fi
    unzip -q "$TMP_ZIP" -d "$MAVEN_HOME"
    rm "$TMP_ZIP"
fi

MAVEN_BINARY=$(find "$MAVEN_HOME" -name "mvn" -type f | head -1)
exec "$MAVEN_BINARY" "$@"
