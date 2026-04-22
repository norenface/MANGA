#!/bin/bash
set -e

ANDROID_JAR="/usr/lib/android-sdk/platforms/android-23/android.jar"
AAPT2="/usr/bin/aapt2"
KOTLINC="/usr/bin/kotlinc"
D8="java -cp /usr/lib/android-sdk/build-tools/30.0.3/r8.jar com.android.tools.r8.D8"
APKSIGNER="/usr/bin/apksigner"
KEYTOOL="keytool"

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD="$BASE_DIR/build-out"
mkdir -p "$BUILD/compiled_res" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$BUILD/apk"

echo "=== 1. aapt2 compile resources ==="
$AAPT2 compile \
  --dir "$BASE_DIR/res" \
  -o "$BUILD/compiled_res/"

echo "=== 2. aapt2 link ==="
$AAPT2 link \
  --manifest "$BASE_DIR/AndroidManifest.xml" \
  -I "$ANDROID_JAR" \
  --java "$BUILD/gen" \
  --auto-add-overlay \
  -o "$BUILD/resources.ap_" \
  "$BUILD/compiled_res/"*.flat

echo "=== 3. Compile Kotlin ==="
$KOTLINC \
  -cp "$ANDROID_JAR" \
  -d "$BUILD/classes.jar" \
  "$BASE_DIR/src/com/manga/translator/"*.kt \
  "$BUILD/gen/com/manga/translator/R.java"

echo "=== 4. DEX compile ==="
$D8 \
  --lib "$ANDROID_JAR" \
  --output "$BUILD/dex/" \
  "$BUILD/classes.jar"

echo "=== 5. Package APK ==="
cp "$BUILD/resources.ap_" "$BUILD/apk/app-unsigned.apk"
cd "$BUILD/dex" && zip -j "$BUILD/apk/app-unsigned.apk" classes.dex
cd "$BASE_DIR"

echo "=== 6. Generate debug keystore (if needed) ==="
if [ ! -f "$BUILD/debug.keystore" ]; then
  $KEYTOOL -genkey -v \
    -keystore "$BUILD/debug.keystore" \
    -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
fi

echo "=== 7. Sign APK ==="
$APKSIGNER sign \
  --ks "$BUILD/debug.keystore" \
  --ks-key-alias androiddebugkey \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$BUILD/app-debug.apk" \
  "$BUILD/apk/app-unsigned.apk"

echo ""
echo "=============================="
echo "✓ Build SUCCESS!"
echo "APK: $BUILD/app-debug.apk"
ls -lh "$BUILD/app-debug.apk"
echo "=============================="
