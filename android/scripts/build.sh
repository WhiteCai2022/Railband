#!/usr/bin/env bash
set -euo pipefail
# Linux build host: JDK 17, make, zip, Android SDK 36 and NDK 28.2.
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-/opt/rail-android/sdk}"
NDK="$SDK/ndk/28.2.13676358"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
BT="$SDK/build-tools/36.0.0"
OUT="$ROOT/android/build"
SRC="$ROOT/android/app/src/main"
mkdir -p "$OUT" "$OUT/classes" "$OUT/dex" "$OUT/package/lib" "$OUT/assets"
for ABI in arm64-v8a x86_64; do
    case "$ABI" in arm64-v8a) TARGET=aarch64-linux-android; HOST=aarch64-linux-android;; x86_64) TARGET=x86_64-linux-android; HOST=x86_64-linux-android;; esac
    mkdir -p "$OUT/native-$ABI" "$OUT/package/lib/$ABI"
    (
        cd "$OUT/native-$ABI"
        if [ ! -f Makefile ]; then
            CC="$TC/${TARGET}28-clang" AR="$TC/llvm-ar" RANLIB="$TC/llvm-ranlib" STRIP="$TC/llvm-strip" CFLAGS="-O2 -fPIE" LDFLAGS="-pie -Wl,-z,max-page-size=16384" "$ROOT/configure" --host="$HOST" --disable-shared --enable-static --without-openssl > configure.log 2>&1
        fi
        make -j2 > build.log 2>&1
        cp src/iperf3 "$OUT/package/lib/$ABI/libiperf_exec.so"
        "$TC/llvm-strip" "$OUT/package/lib/$ABI/libiperf_exec.so"
    )
done
cp "$ROOT/LICENSE" "$OUT/assets/IPERF-LICENSE.txt"
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/resources.zip"
"$BT/aapt2" link -o "$OUT/base.apk" --manifest "$SRC/AndroidManifest.xml" -I "$SDK/platforms/android-36/android.jar" -A "$OUT/assets" "$OUT/resources.zip"
find "$SRC/java" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 --release 8 -classpath "$SDK/platforms/android-36/android.jar" -d "$OUT/classes" @"$OUT/sources.txt"
jar cf "$OUT/classes.jar" -C "$OUT/classes" .
"$BT/d8" --lib "$SDK/platforms/android-36/android.jar" --min-api 28 --output "$OUT/dex" "$OUT/classes.jar"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
cp "$OUT/dex/classes.dex" "$OUT/package/"
(cd "$OUT/package" && zip -q -r "$OUT/unsigned.apk" classes.dex lib)
"$BT/zipalign" -f -P 16 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
# This development key stays outside source control. Keep it for in-place updates.
KEY="${RAIL_DEBUG_KEYSTORE:-/opt/rail-android/rail-debug.jks}"
if [ ! -f "$KEY" ]; then
    keytool -genkeypair -keystore "$KEY" -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=RailBand Development,O=RailBand,C=CN" >/dev/null 2>&1
    chmod 600 "$KEY"
fi
"$BT/apksigner" sign --ks "$KEY" --ks-pass pass:android --key-pass pass:android --out "$OUT/rail-bandwidth-mvp.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify --verbose "$OUT/rail-bandwidth-mvp.apk"
"$BT/zipalign" -c -P 16 4 "$OUT/rail-bandwidth-mvp.apk"
sha256sum "$OUT/rail-bandwidth-mvp.apk"
