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

# ---- 双版本签名配置 ----
# 正式版：release keystore（只生成一次并持久化，以后所有正式版都用它）。
#   密码不写在脚本里（脚本要推到 GitHub），从环境变量 RELEASE_KEYSTORE_PASS
#   或 /data/user/work/ollama-release.pass 读取，防止泄露。
# debug 版：debug keystore，给作者自己测试用，命名带 _debug 后缀。
RELEASE_KS=/data/user/work/ollama-release.keystore
RELEASE_ALIAS=ollama-release
DEBUG_KS=/data/user/work/ollama-debug.keystore
DEBUG_PASS=android
DEBUG_ALIAS=androiddebugkey

RELEASE_PASS="${RELEASE_KEYSTORE_PASS:-}"
if [ -z "$RELEASE_PASS" ] && [ -f /data/user/work/ollama-release.pass ]; then
  RELEASE_PASS=$(tr -d '\r\n' < /data/user/work/ollama-release.pass)
fi
if [ -z "$RELEASE_PASS" ]; then
  echo "错误：缺少正式版签名密码（设置环境变量 RELEASE_KEYSTORE_PASS 或提供 /data/user/work/ollama-release.pass）" >&2
  exit 1
fi

# 版本名（如 1.3.15）→ 输出文件名
VER=$(grep -o 'android:versionName="[^"]*"' "$PROJ/AndroidManifest.xml" | head -1 | cut -d'"' -f2)
echo "== 构建版本 $VER =="

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

echo "== 7. 正式签名 keystore（首次自动生成并持久化）=="
if [ ! -f "$RELEASE_KS" ]; then
  keytool -genkeypair -keystore "$RELEASE_KS" -alias "$RELEASE_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$RELEASE_PASS" -keypass "$RELEASE_PASS" \
    -dname "CN=Ollama Android,O=Ollama Android,C=CN"
  echo "已生成正式签名密钥：$RELEASE_KS"
  echo "警告：这把密钥请务必备份！丢失后已装的正式版将无法覆盖升级。"
fi

echo "== 8. 签名（同一份 APK 签两把密钥）=="
sign() {
  local ks=$1 alias=$2 pass=$3 name=$4
  rm -f "$OUTDIR/$name"
  "$BT/apksigner" sign \
      --ks "$ks" --ks-pass "pass:$pass" --key-pass "pass:$pass" \
      --ks-key-alias "$alias" \
      --out "$OUTDIR/$name" "$OUTDIR/app-aligned.apk"
  "$BT/apksigner" verify --print-certs "$OUTDIR/$name" | head -2
  echo "  -> $name"
}

sign "$RELEASE_KS" "$RELEASE_ALIAS" "$RELEASE_PASS" "Ollama-arm64-v${VER}.apk"
sign "$DEBUG_KS" "$DEBUG_ALIAS" "$DEBUG_PASS" "Ollama-arm64-v${VER}_debug.apk"

echo "== DONE =="
ls -la "$OUTDIR"/Ollama-arm64-v${VER}*.apk
