#!/usr/bin/env bash
# Builds the debug APK and copies it into builds/, named after versionName.
set -euo pipefail

cd "$(dirname "$0")"

VERSION=$(grep 'versionName = ' app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)".*/\1/')
APK="builds/whatsapp-transcriber-v${VERSION}.apk"

# The needle engine is a prebuilt binary, so the build refuses it if it can reach the network.
SDK_DIR="${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' local.properties)}"
NM=$(ls "$SDK_DIR"/ndk/27.1.12297006/toolchains/llvm/prebuilt/*/bin/llvm-nm | head -1)
FORBIDDEN='^(socket|socketpair|connect|send|sendto|sendmsg|recvfrom|recvmsg|getaddrinfo|gethostbyname|bind|listen|accept|dlopen|android_dlopen_ext|dlsym|execv|execve|fork|popen|system|syscall)$'
IMPORTS=$("$NM" -u third_party/needle/android-arm64/libneedle.a | awk '{print $NF}')
[ -n "$IMPORTS" ] || { echo "llvm-nm listed no imports for libneedle.a" >&2; exit 1; }
if grep -E "$FORBIDDEN" <<<"$IMPORTS"; then
  echo "libneedle.a imports the functions above, refusing to build" >&2
  exit 1
fi

./gradlew :app:assembleDebug
mkdir -p builds
cp app/build/outputs/apk/debug/app-debug.apk "$APK"

printf '\n%s  %s\n' "$(du -h "$APK" | cut -f1)" "$APK"
printf 'install with: adb install -r %s\n' "$APK"
