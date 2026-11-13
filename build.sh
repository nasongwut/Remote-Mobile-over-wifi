#!/usr/bin/env bash
# =============================================================================
#  Mobile Remote: build (and optionally install) the APK
#
#  Usage:
#    ./build.sh             build MobileRemote.apk
#    ./build.sh install     build, then install on every phone connected over adb
#    ./build.sh clean       remove build output, then build
#
#  First time? Run ./setup-dev.sh to install JDK, Android SDK, and Gradle.
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")"

[ -f "$HOME/.mobileremote-env" ] && source "$HOME/.mobileremote-env"

if [ -z "${ANDROID_HOME:-}" ] || [ -z "${JAVA_HOME:-}" ] || [ ! -x ./gradlew ]; then
  echo "Development environment not found. Running ./setup-dev.sh first…"
  ./setup-dev.sh --no-build
  source "$HOME/.mobileremote-env"
fi
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties

if [ "${1:-}" = "clean" ]; then
  ./gradlew clean -q
fi

echo "==> Building"
./gradlew assembleDebug -q
cp app/build/outputs/apk/debug/app-debug.apk ./MobileRemote.apk
echo "==> Done: $(pwd)/MobileRemote.apk"

if [ "${1:-}" = "install" ]; then
  ADB="$ANDROID_HOME/platform-tools/adb"
  devices=$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1}')
  [ -n "$devices" ] || { echo "No phone connected. Enable USB debugging and run: adb devices"; exit 1; }
  for d in $devices; do
    echo "==> Installing on $d"
    "$ADB" -s "$d" install --user 0 -r MobileRemote.apk
  done
fi
