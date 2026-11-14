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

# 发行版本：main（默认）/ multi（多通道版）
#   bash build.sh          → out/app-manager.apk          com.henry.appmanager
#   bash build.sh multi    → out/app-manager-multi.apk    com.henry.appmanager.multi
# 两个包名不同，可以同时安装、各自独立更新。
EDITION="${1:-${EDITION:-main}}"
case "$EDITION" in
  main)  RENAME_PKG=""                        ; APK_NAME="app-manager.apk" ;;
  multi) RENAME_PKG="com.henry.appmanager.multi" ; APK_NAME="app-manager-multi.apk" ;;
  *) echo "未知版本：$EDITION（可用 main / multi）"; exit 1 ;;
esac

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

echo "== 0. 生成资源（从 frontend.py 提取 HTML 与名称映射） =="
# 🔴 gen_manifest.py 必须排第一：gen_assets.py 要从 AndroidManifest.xml 读版本号
#    注入前端。顺序反了会读到上一次构建的版本，界面显示的版本号永远慢一版。
"$PYTHON" "$BASE/tools/gen_manifest.py" "$EDITION"
"$PYTHON" "$BASE/tools/gen_assets.py"
"$PYTHON" "$BASE/tools/gen_icon.py"

echo "== 0.5 检查 JS 接口注解（漏一个就会「操作失败且无日志」） =="
"$PYTHON" "$BASE/tools/check_js_iface.py"

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$OUT"

echo "== 1. aapt2 compile =="
"$BT/aapt2" compile --dir "$BASE/res" -o "$BUILD/res.zip"

echo "== 2. aapt2 link =="
LINK_EXTRA=()
if [ -n "$RENAME_PKG" ]; then
  # 等价于 Gradle 的 applicationId：只改最终 manifest 的包名，
  # Java 类包名和 R.java 都不变（组件名已在 gen_manifest 里写成全限定）。
  LINK_EXTRA=(--rename-manifest-package "$RENAME_PKG")
fi

"$BT/aapt2" link -o "$BUILD/base.apk" \
  -I "$PLATFORM" \
  --manifest "$BASE/src/AndroidManifest.xml" \
  -R "$BUILD/res.zip" \
  -A "$BASE/assets" \
  --java "$BUILD/gen" \
  ${LINK_EXTRA[@]+"${LINK_EXTRA[@]}"} \
  --auto-add-overlay

echo "== 3. javac =="
find "$BASE/src/java" -name "*.java" > "$BUILD/sources.txt"
find "$BUILD/gen" -name "*.java" >> "$BUILD/sources.txt"
"$JAVAC" -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$PLATFORM" \
  -classpath "$BASE/libs/shizuku-api.jar;$BASE/libs/shizuku-provider.jar;$BASE/libs/shizuku-aidl.jar" \
  -d "$BUILD/classes" \
  @"$BUILD/sources.txt"

echo "== 4. d8 =="
find "$BUILD/classes" -name "*.class" > "$BUILD/classlist.txt"
"$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --lib "$PLATFORM" --min-api 26 --release \
  --output "$BUILD/dex" \
  @"$BUILD/classlist.txt" \
  "$BASE/libs/shizuku-api.jar" "$BASE/libs/shizuku-provider.jar" "$BASE/libs/shizuku-aidl.jar"

echo "== 5. package =="
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
( cd "$BUILD/dex" && zip -q -u "$BUILD/unsigned.apk" classes.dex )

echo "== 6. zipalign =="
"$BT/zipalign" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "== 7. sign =="
"$JAVA" -jar "$BT/lib/apksigner.jar" sign \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KEY_PASS" --ks-key-alias "$KEY_ALIAS" \
  --out "$OUT/$APK_NAME" "$BUILD/aligned.apk"

"$JAVA" -jar "$BT/lib/apksigner.jar" verify "$OUT/$APK_NAME"

echo "OK: $OUT/$APK_NAME  (edition=$EDITION)"
ls -lh "$OUT/$APK_NAME"
