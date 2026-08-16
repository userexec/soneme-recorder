#!/bin/sh
# Small self-bootstrapping Gradle launcher used because this source bundle is
# generated without a binary gradle-wrapper.jar. It installs the Gradle version
# required by Android Gradle Plugin 8.7 into GRADLE_USER_HOME, then execs it.
set -eu
VERSION=8.9
BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/soneme-bootstrap"
HOME_DIR="$BASE/gradle-$VERSION"
ZIP="$BASE/gradle-$VERSION-bin.zip"
if [ ! -x "$HOME_DIR/bin/gradle" ]; then
    mkdir -p "$BASE"
    URL="https://services.gradle.org/distributions/gradle-$VERSION-bin.zip"
    if command -v curl >/dev/null 2>&1; then
        curl -fL --retry 2 -o "$ZIP" "$URL"
    elif command -v wget >/dev/null 2>&1; then
        wget -O "$ZIP" "$URL"
    else
        echo "Need curl or wget to bootstrap Gradle $VERSION" >&2
        exit 1
    fi
    command -v unzip >/dev/null 2>&1 || { echo "Need unzip to bootstrap Gradle" >&2; exit 1; }
    rm -rf "$HOME_DIR"
    unzip -q "$ZIP" -d "$BASE"
    rm -f "$ZIP"
fi
exec "$HOME_DIR/bin/gradle" "$@"
