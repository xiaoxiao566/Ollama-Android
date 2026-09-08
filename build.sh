#!/usr/bin/env bash
set -euo pipefail

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"

BT=/data/user/work/sdk/bt/android-13
ANDROID_JAR=/data/user/work/sdk/platforms/android-13/android.jar
JAVAC="$JAVA_HOME/bin/javac"
PROJ=/data/user/work/ollama-project
ASSETS="$PROJ/assets"
OUTDIR="$PROJ/build"
# 统一使用固定的 debug 签名：keystore 只生成一次并持久化，
# 之后每次构建都用同一把密钥，避免签名变化导致必须先卸载再安装。
KEYSTORE=/data/user/work/ollama-debug.keystore
KS_PASS=android
KS_ALIAS=androiddebugkey

rm -rf "$OUTDIR" && mkdir -p "$OUTDIR/gen" "$OUTDIR/classes" "$OUTDIR/dex" "$OUTDIR/pkg"

cd "$PROJ"

echo "== 1. aapt2 compile resources =="
"$BT/aapt2" compile --dir res -o "$OUTDIR/res.zip"

echo "== 2. aapt2 link =="
"$BT/aapt2" link -o "$OUTDIR/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml \
    --java "$OUTDIR/gen" \
    --auto-add-overlay \
    "$OUTDIR/res.zip"

echo "== 3. javac =="
find "$OUTDIR/gen" src -name '*.java' > "$OUTDIR/sources.txt"
"$JAVAC" -source 1.8 -target 1.8 -bootclasspath "$ANDROID_JAR" -encoding UTF-8 \
    -d "$OUTDIR/classes" @"$OUTDIR/sources.txt" 2>&1 | tee "$OUTDIR/javac.log"

echo "== 4. d8 dex =="
find "$OUTDIR/classes" -name '*.class' > "$OUTDIR/classes.txt"
"$BT/d8" --release --min-api 28 --lib "$ANDROID_JAR" \
    --output "$OUTDIR/dex" @"$OUTDIR/classes.txt"

echo "== 5. package =="
cp "$OUTDIR/base.apk" "$OUTDIR/pkg/app-unsigned.apk"
# 把 classes.dex 原地加入（存储而非压缩，避免 dex 二次压缩问题）
cp "$OUTDIR/dex/classes.dex" "$OUTDIR/pkg/classes.dex"
(cd "$OUTDIR/pkg" && zip -X -j app-unsigned.apk classes.dex >/dev/null)
# 打包 assets（bin/ollama + lib/ollama/**）
cp -r "$ASSETS" "$OUTDIR/pkg/assets"
(cd "$OUTDIR/pkg" && zip -X -r app-unsigned.apk assets >/dev/null)

echo "== 6. zipalign =="
"$BT/zipalign" -f 4 "$OUTDIR/pkg/app-unsigned.apk" "$OUTDIR/app-aligned.apk"

echo "== 7. keystore (debug, 固定签名) =="
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=Android Debug,O=Android,C=US"
fi

echo "== 8. sign =="
"$BT/apksigner" sign \
    --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --ks-key-alias "$KS_ALIAS" \
    --out "$OUTDIR/Ollama-arm64.apk" "$OUTDIR/app-aligned.apk"

echo "== 9. verify =="
"$BT/apksigner" verify --print-certs "$OUTDIR/Ollama-arm64.apk" | head -3

echo "== DONE =="
ls -la "$OUTDIR/Ollama-arm64.apk"