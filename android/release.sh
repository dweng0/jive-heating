#!/usr/bin/env bash
# Builds the signed release APK and puts it, with Gradle's output-metadata.json (the version the app's update check
# reads), in android-dist/ for the next Docker image. Signing settings come from the environment, or from
# ~/.config/jive-heating/release.env when that exists. See docs/android-release.md.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
env_file="${HEATING_RELEASE_ENV:-$HOME/.config/jive-heating/release.env}"
[ -z "${HEATING_RELEASE_KEYSTORE:-}" ] && [ -f "$env_file" ] && . "$env_file"
"$here/gradlew" -p "$here" :app:assembleRelease --console=plain -q
out="$here/app/build/outputs/apk/release"
cp "$out/app-release.apk" "$here/../android-dist/heating.apk"
cp "$out/output-metadata.json" "$here/../android-dist/output-metadata.json"
grep -o '"versionName": "[^"]*"' "$out/output-metadata.json"
echo "android-dist/ ready: rebuild the Docker image to offer this version."
