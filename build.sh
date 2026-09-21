#!/usr/bin/env bash
# Builds the debug APK and copies it into builds/, named after versionName.
set -euo pipefail

cd "$(dirname "$0")"

VERSION=$(grep 'versionName = ' app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)".*/\1/')
APK="builds/whatsapp-transcriber-v${VERSION}.apk"

./gradlew :app:assembleDebug
mkdir -p builds
cp app/build/outputs/apk/debug/app-debug.apk "$APK"

printf '\n%s  %s\n' "$(du -h "$APK" | cut -f1)" "$APK"
printf 'install with: adb install -r %s\n' "$APK"
