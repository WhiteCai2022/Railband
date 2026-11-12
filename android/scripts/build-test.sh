#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-/opt/rail-android/sdk}"
BT="$SDK/build-tools/36.0.0"
OUT="$ROOT/android/build/test"
mkdir -p "$OUT/classes" "$OUT/dex"
javac -encoding UTF-8 --release 8 -classpath "$SDK/platforms/android-36/android.jar:$ROOT/android/build/classes.jar" -d "$OUT/classes" "$ROOT/android/scripts/SmokeTest.java" "$ROOT/android/scripts/V3SmokeTest.java" "$ROOT/android/scripts/V4LocationTest.java" "$ROOT/android/scripts/V4IntervalTest.java"
jar cf "$OUT/classes.jar" -C "$OUT/classes" .
"$BT/d8" --lib "$SDK/platforms/android-36/android.jar" --classpath "$ROOT/android/build/classes.jar" --min-api 28 --output "$OUT/dex" "$OUT/classes.jar"
"$BT/aapt2" link -o "$OUT/unsigned.apk" --manifest "$ROOT/android/scripts/test-manifest.xml" -I "$SDK/platforms/android-36/android.jar"
(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "${RAIL_DEBUG_KEYSTORE:-/opt/rail-android/rail-debug.jks}" --ks-pass pass:android --out "$OUT/smoke-test.apk" "$OUT/aligned.apk"
