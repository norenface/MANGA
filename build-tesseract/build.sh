#!/bin/bash
# Tesseract版 ビルドスクリプト
# 依存ライブラリのダウンロードからAPK署名まで全自動実行
set -e

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD="$BASE_DIR/build-out"
LIBS="$BASE_DIR/libs"
ANDROID_JAR="/usr/lib/android-sdk/platforms/android-23/android.jar"
AAPT2="/usr/bin/aapt2"
KOTLINC="/usr/bin/kotlinc"
D8="java -cp /usr/lib/android-sdk/build-tools/30.0.3/r8.jar com.android.tools.r8.D8"
APKSIGNER="/usr/bin/apksigner"
MAVEN="https://repo1.maven.org/maven2"

mkdir -p "$BUILD"/{compiled_res,gen,dex,apk,work} "$LIBS" "$BASE_DIR/jniLibs"

# ============================================================
# STEP 1: 依存JARダウンロード
# ============================================================
echo "=== 1/8 依存ライブラリをダウンロード中 ==="

download() {
    local file="$LIBS/$1"
    local url="$2"
    if [ ! -f "$file" ]; then
        echo "  Downloading: $1"
        curl -sL --max-time 120 "$url" -o "$file" || { echo "ERROR: Failed to download $1"; exit 1; }
        echo "  Done: $(du -sh "$file" | cut -f1)"
    else
        echo "  Cached: $1"
    fi
}

# tess-two (Tesseract for Android, AAR)
download "tess-two.aar" \
    "$MAVEN/com/rmtheis/tess-two/9.1.0/tess-two-9.1.0.aar"

# kotlin-stdlib は kotlinc 1.3 の bundled 版を使う (外部 jar 不要)

# ============================================================
# STEP 2: tess-two AAR を展開 → JAR + native .so 取得
# ============================================================
echo "=== 2/8 tess-two AAR を展開中 ==="
TESS_EXTRACT="$BUILD/tess-extract"
mkdir -p "$TESS_EXTRACT"

if [ ! -f "$LIBS/tess-two-classes.jar" ]; then
    cd "$TESS_EXTRACT"
    unzip -qo "$LIBS/tess-two.aar"
    cp classes.jar "$LIBS/tess-two-classes.jar"

    # Native ライブラリ (.so) を展開
    mkdir -p "$BASE_DIR/jniLibs"
    # jni/ の下に各ABI別.soが入っている
    if [ -d "jni" ]; then
        cp -r jni/* "$BASE_DIR/jniLibs/"
        echo "  Native libs: $(find "$BASE_DIR/jniLibs" -name '*.so' | wc -l) files"
    fi
    cd "$BASE_DIR"
fi

# ============================================================
# STEP 3: Korean tessdata ダウンロード (fast版 ~4MB)
# ============================================================
echo "=== 3/8 韓国語テッセラクトデータをダウンロード中 ==="
KOR_DATA="$BASE_DIR/assets/tessdata/kor.traineddata"
if [ ! -f "$KOR_DATA" ] || [ "$(wc -c < "$KOR_DATA")" -lt 1000000 ]; then
    echo "  Downloading kor.traineddata (fast version ~4MB)..."
    # tessdata_fast はサイズが小さくモバイル向き
    curl -sL --max-time 120 \
        "https://github.com/tesseract-ocr/tessdata_fast/raw/main/kor.traineddata" \
        -o "$KOR_DATA" || {
        # フォールバック: 別のミラーから試みる
        echo "  Trying alternative source..."
        curl -sL --max-time 180 \
            "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/kor.traineddata" \
            -o "$KOR_DATA"
    }
    echo "  Done: $(du -sh "$KOR_DATA" | cut -f1)"
fi

# ============================================================
# STEP 4: リソースコンパイル (aapt2)
# ============================================================
echo "=== 4/8 リソースをコンパイル中 ==="
$AAPT2 compile --dir "$BASE_DIR/res" -o "$BUILD/compiled_res/"

# ============================================================
# STEP 5: リソースリンク → R.java + resources.ap_ 生成
# ============================================================
echo "=== 5/8 リソースをリンク中 ==="
$AAPT2 link \
    --manifest "$BASE_DIR/AndroidManifest.xml" \
    -I "$ANDROID_JAR" \
    --java "$BUILD/gen" \
    --auto-add-overlay \
    -o "$BUILD/resources.ap_" \
    "$BUILD/compiled_res/"*.flat

# ============================================================
# STEP 6: Kotlin コンパイル
# ============================================================
echo "=== 6/8 Kotlin ソースをコンパイル中 ==="

# クラスパス: android.jar + tess-two のみ (stdlib は kotlinc 内蔵版)
CP="$ANDROID_JAR:$LIBS/tess-two-classes.jar"

$KOTLINC \
    -cp "$CP" \
    -include-runtime \
    -d "$BUILD/classes.jar" \
    "$BASE_DIR/src/com/manga/translator/"*.kt \
    "$BASE_DIR/src/com/manga/translator/ocr/"*.kt \
    "$BASE_DIR/src/com/manga/translator/translation/"*.kt \
    "$BASE_DIR/src/com/manga/translator/ui/"*.kt \
    "$BASE_DIR/src/com/manga/translator/model/"*.kt \
    "$BUILD/gen/com/manga/translator/R.java"

# ============================================================
# STEP 7: DEX変換 + APKパッケージング
# ============================================================
echo "=== 7/8 DEX変換 & APKパッケージング ==="

# DEX変換 (-include-runtime済みclasses.jar + tess-two)
$D8 \
    --lib "$ANDROID_JAR" \
    --output "$BUILD/dex/" \
    "$BUILD/classes.jar" \
    "$LIBS/tess-two-classes.jar"

# APK組み立て (resources.ap_ をベースに追加していく)
cp "$BUILD/resources.ap_" "$BUILD/apk/app-unsigned.apk"

# DEX追加
cd "$BUILD/dex" && zip -j "$BUILD/apk/app-unsigned.apk" classes.dex && cd "$BASE_DIR"

# assets/ 追加 (tessdata)
cd "$BASE_DIR" && zip -r "$BUILD/apk/app-unsigned.apk" assets/ && cd "$BASE_DIR"

# Native .so 追加 (lib/<abi>/libname.so 形式で格納)
if [ -d "$BASE_DIR/jniLibs" ] && [ "$(ls -A "$BASE_DIR/jniLibs")" ]; then
    cd "$BASE_DIR/jniLibs"
    # APK内パス: lib/<abi>/*.so
    for abi_dir in */; do
        abi="${abi_dir%/}"
        mkdir -p "$BUILD/work/lib/$abi"
        cp "$abi_dir"*.so "$BUILD/work/lib/$abi/" 2>/dev/null || true
    done
    cd "$BUILD/work"
    # -0 で非圧縮格納 (targetSdk>=23 では extractNativeLibs=false がデフォルトのため
    # .so が DEFLATE 圧縮されていると System.loadLibrary が UnsatisfiedLinkError を投げる)
    zip -r -0 "$BUILD/apk/app-unsigned.apk" lib/ 2>/dev/null || true
    cd "$BASE_DIR"
fi

# ============================================================
# STEP 8: zipalign + APK署名
# ============================================================
echo "=== 8/8 zipalign & APK署名 ==="

# zipalign: 署名前に実行する必要がある
zipalign -f 4 "$BUILD/apk/app-unsigned.apk" "$BUILD/apk/app-aligned.apk"

if [ ! -f "$BUILD/debug.keystore" ]; then
    keytool -genkey -v \
        -keystore "$BUILD/debug.keystore" \
        -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android \
        -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
fi

$APKSIGNER sign \
    --ks "$BUILD/debug.keystore" \
    --ks-key-alias androiddebugkey \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$BUILD/app-tesseract-debug.apk" \
    "$BUILD/apk/app-aligned.apk"

echo ""
echo "============================================"
echo "✓ ビルド完了!"
echo "APK: $BUILD/app-tesseract-debug.apk"
ls -lh "$BUILD/app-tesseract-debug.apk"
echo "============================================"
