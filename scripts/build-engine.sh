#!/usr/bin/env bash
# Builds app/libs/engine.aar (Xray-core + gVisor glue from /engine) with
# gomobile. Needs Go and the Android SDK with an NDK installed; set
# ANDROID_HOME, and ANDROID_NDK_HOME if the NDK is not under $ANDROID_HOME/ndk.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT/engine"

# Use the gomobile matching the x/mobile version pinned in go.mod.
MOBILE=$(go list -m -f '{{.Version}}' golang.org/x/mobile)
go install "golang.org/x/mobile/cmd/gomobile@$MOBILE" "golang.org/x/mobile/cmd/gobind@$MOBILE"
export PATH="$(go env GOPATH)/bin:$PATH"
gomobile init

mkdir -p "$ROOT/app/libs"
# Keep the targets in sync with abiFilters in app/build.gradle.kts.
gomobile bind -v \
    -target android/arm64,android/arm,android/amd64 \
    -androidapi 26 \
    -trimpath -ldflags "-s -w -buildid=" \
    -o "$ROOT/app/libs/engine.aar" .
echo "built $ROOT/app/libs/engine.aar"
