#!/usr/bin/env bash
# 构建独立的 App Manager APK —— 不依赖 Gradle、不依赖 Android Studio。
# 只用到 JDK、Android SDK build-tools 和 python3。
#
# 可用环境变量覆盖：
#   ANDROID_SDK           Android SDK 根目录（默认 $ANDROID_HOME）
#   ANDROID_BUILD_TOOLS   build-tools 版本号（默认 35.0.0）
#   ANDROID_PLATFORM      platforms 目录名（默认 android-35）
#   JAVA_HOME             JDK 根目录（不设就用 PATH 里的 java/javac）
#   PYTHON                解释器（默认 python3）
#   KEYSTORE / KS_PASS / KEY_ALIAS / KEY_PASS   签名相关
# 也可以把上面这些写进 appmanager/local.env（该文件不进仓库）。
set -euo pipefail

BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Git Bash 下 pwd 给的是 /d/xxx 形式，原生 Windows 工具（javac/aapt2）认不了，
# 换成 D:/xxx；Linux/macOS 上没有 cygpath，保持原样。
if command -v cygpath >/dev/null 2>&1; then
  BASE="$(cygpath -m "$BASE")"
fi
OUT="$BASE/out"
BUILD="$BASE/build"

if [ -f "$BASE/local.env" ]; then . "$BASE/local.env"; fi

PYTHON="${PYTHON:-python3}"
ANDROID_SDK="${ANDROID_SDK:-${ANDROID_HOME:-}}"
BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-35.0.0}"
PLATFORM_NAME="${ANDROID_PLATFORM:-android-35}"

BT="$ANDROID_SDK/build-tools/$BUILD_TOOLS"
PLATFORM="$ANDROID_SDK/platforms/$PLATFORM_NAME/android.jar"

KEYSTORE="${KEYSTORE:-$BASE/release.keystore}"
KS_PASS="${KS_PASS:-android}"
KEY_PASS="${KEY_PASS:-$KS_PASS}"
KEY_ALIAS="${KEY_ALIAS:-appmanager}"

JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"

[ -n "$ANDROID_SDK" ] || { echo "请设置 ANDROID_SDK（Android SDK 根目录）"; exit 1; }
[ -f "$PLATFORM" ] || { echo "找不到 $PLATFORM"; exit 1; }
[ -x "$BT/aapt2" ] || [ -x "$BT/aapt2.exe" ] || { echo "找不到 $BT/aapt2"; exit 1; }

# 首次构建自动生成一个自签名密钥，省得再手动准备
if [ ! -f "$KEYSTORE" ]; then
  echo "== 生成签名密钥 $KEYSTORE =="
  "$KEYTOOL" -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 \
    -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KEY_PASS" \
    -dname "CN=App Manager, OU=Dev, O=Local, L=Local, S=Local, C=CN"
fi

echo "== 0. 生成资源（从 server.py 提取 HTML 与名称映射） =="
"$PYTHON" "$BASE/tools/gen_assets.py"
"$PYTHON" "$BASE/tools/gen_icon.py"
"$PYTHON" "$BASE/tools/gen_manifest.py"

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$OUT"

echo "== 1. aapt2 compile =="
"$BT/aapt2" compile --dir "$BASE/res" -o "$BUILD/res.zip"

echo "== 2. aapt2 link =="
"$BT/aapt2" link -o "$BUILD/base.apk" \
  -I "$PLATFORM" \
  --manifest "$BASE/src/AndroidManifest.xml" \
  -R "$BUILD/res.zip" \
  -A "$BASE/assets" \
  --java "$BUILD/gen" \
  --auto-add-overlay

echo "== 3. javac =="
find "$BASE/src/java" -name "*.java" > "$BUILD/sources.txt"
find "$BUILD/gen" -name "*.java" >> "$BUILD/sources.txt"
"$JAVAC" -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$PLATFORM" \
  -d "$BUILD/classes" \
  @"$BUILD/sources.txt"

echo "== 4. d8 =="
find "$BUILD/classes" -name "*.class" > "$BUILD/classlist.txt"
"$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --lib "$PLATFORM" --min-api 26 --release \
  --output "$BUILD/dex" \
  @"$BUILD/classlist.txt"

echo "== 5. package =="
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
( cd "$BUILD/dex" && zip -q -u "$BUILD/unsigned.apk" classes.dex )

echo "== 6. zipalign =="
"$BT/zipalign" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "== 7. sign =="
"$JAVA" -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KEY_PASS" --ks-key-alias "$KEY_ALIAS" \
  --out "$OUT/app-manager.apk" "$BUILD/aligned.apk"

"$JAVA" -jar "$BT/lib/apksigner.jar" verify "$OUT/app-manager.apk"

echo "OK: $OUT/app-manager.apk"
ls -lh "$OUT/app-manager.apk"
