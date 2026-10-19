#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd)"
android_jar="/opt/android-sdk/platforms/android-33/android.jar"
build_tools="/opt/android-sdk/build-tools/36.0.0"
build_dir="$project_dir/build"
dist_dir="$project_dir/dist"
keystore="${ANDROID_DEBUG_KEYSTORE:-/root/.android/debug.keystore}"
if [[ ! -f "$keystore" ]]; then
    keystore="$project_dir/.debug.keystore"
    if [[ ! -f "$keystore" ]]; then
        keytool -genkeypair -noprompt \
            -keystore "$keystore" -storepass android -keypass android \
            -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
            -dname "CN=Android Debug,O=Android,C=US"
    fi
fi

test -f "$android_jar"
test -x "$build_tools/d8"
test -f "$project_dir/vendor/trilead-runtime.dex"
test -f "$project_dir/vendor/arm64-v8a/libtun2socks.so"
test -f "$project_dir/vendor/armeabi-v7a/libtun2socks.so"

rm -rf "$build_dir"
mkdir -p "$build_dir/gen" "$build_dir/stubs" "$build_dir/classes" \
    "$build_dir/dex" "$build_dir/apk-add/lib/arm64-v8a" \
    "$build_dir/apk-add/lib/armeabi-v7a" "$dist_dir"

aapt package -f -m \
    -M "$project_dir/AndroidManifest.xml" \
    -S "$project_dir/res" \
    -I "$android_jar" \
    -J "$build_dir/gen" \
    -F "$build_dir/resources-unsigned.apk"

find "$project_dir/compile_stubs" -name '*.java' -print0 \
    | xargs -0 javac -encoding UTF-8 -source 8 -target 8 \
        -classpath "$android_jar" -d "$build_dir/stubs"
jar cf "$build_dir/compile-stubs.jar" -C "$build_dir/stubs" .

find "$project_dir/src" "$build_dir/gen" -name '*.java' -print0 \
    | xargs -0 javac -encoding UTF-8 -source 8 -target 8 \
        -classpath "$android_jar:$build_dir/stubs" -d "$build_dir/classes"

jar cf "$build_dir/app-classes.jar" -C "$build_dir/classes" .
"$build_tools/d8" --release --min-api 23 --lib "$android_jar" \
    --classpath "$build_dir/compile-stubs.jar" \
    --output "$build_dir/dex" "$build_dir/app-classes.jar"

cp "$build_dir/resources-unsigned.apk" "$build_dir/app-unsigned.apk"
cp "$project_dir/vendor/trilead-runtime.dex" "$build_dir/classes2.dex"
zip -q -j "$build_dir/app-unsigned.apk" \
    "$build_dir/dex/classes.dex" "$build_dir/classes2.dex"

cp "$project_dir/vendor/arm64-v8a/libtun2socks.so" \
    "$build_dir/apk-add/lib/arm64-v8a/libtun2socks.so"
cp "$project_dir/vendor/armeabi-v7a/libtun2socks.so" \
    "$build_dir/apk-add/lib/armeabi-v7a/libtun2socks.so"
(cd "$build_dir/apk-add" && zip -q -r "$build_dir/app-unsigned.apk" lib)

"$build_tools/zipalign" -f 4 "$build_dir/app-unsigned.apk" \
    "$build_dir/app-aligned.apk"
"$build_tools/apksigner" sign \
    --ks "$keystore" \
    --ks-key-alias androiddebugkey \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$dist_dir/Bilola-Tunnel-v1.0.20.apk" \
    "$build_dir/app-aligned.apk"

"$build_tools/apksigner" verify --verbose --print-certs \
    "$dist_dir/Bilola-Tunnel-v1.0.20.apk"
aapt dump badging "$dist_dir/Bilola-Tunnel-v1.0.20.apk"
sha256sum "$dist_dir/Bilola-Tunnel-v1.0.20.apk"
