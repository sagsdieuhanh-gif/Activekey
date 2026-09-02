#!/bin/sh
set -eu
BASE_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$BASE_DIR"
# The Gradle preBuild dependency `prepareCorePackages` downloads/verifies both pinned
# offline core packages. No separate runtime/model download script is required.
if command -v gradle >/dev/null 2>&1; then
  gradle --no-daemon clean assembleDebug
elif [ -f gradle/wrapper/gradle-wrapper.jar ]; then
  chmod +x gradlew
  ./gradlew --no-daemon clean assembleDebug
else
  echo "Gradle CLI/wrapper JAR not found. Build with GitHub Actions/Android Studio or install Gradle 9.5.0." >&2
  exit 2
fi
printf '\nAPK: %s\n' "$BASE_DIR/app/build/outputs/apk/debug/app-debug.apk"
