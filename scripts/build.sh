#!/usr/bin/env bash
# 最小 Android 工具链构建：aapt2 → kotlinc → javac → d8 → zipalign → apksigner
# 与 WebNex CodeAssist 引擎同样的原理：绕过 Gradle，自己串工具。
set -euo pipefail

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$SDK" ]; then echo "未找到 Android SDK（ANDROID_HOME）"; exit 1; fi
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
OUT="build-manual"

rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/classes" "$OUT/dex" "$OUT/gen"

echo "== 1/7 aapt2 compile 资源 =="
"$BT/aapt2" compile --dir app/src/main/res -o "$OUT/compiled/res.zip"

echo "== 2/7 aapt2 link 资源（生成 R.java + resources.ap_）=="
"$BT/aapt2" link -o "$OUT/resources.ap_" -I "$PLATFORM" \
  --manifest app/src/main/AndroidManifest.xml \
  --java "$OUT/gen" \
  --min-sdk-version 21 --target-sdk-version 34 \
  "$OUT/compiled/res.zip"

echo "== 3/7 kotlinc 编译 Kotlin =="
kotlinc app/src/main/kotlin -classpath "$PLATFORM" -jvm-target 17 -d "$OUT/classes"

echo "== 4/7 javac 编译 R.java =="
javac -classpath "$PLATFORM" -d "$OUT/classes" $(find "$OUT/gen" -name '*.java')

echo "== 5/7 d8 转 dex（含 kotlin-stdlib，否则运行时会缺类）=="
KOTLIN_HOME="$(dirname "$(which kotlinc)")/.."
STDLIB="$KOTLIN_HOME/lib/kotlin-stdlib.jar"
"$BT/d8" --lib "$PLATFORM" --min-api 21 --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class') "$STDLIB"

echo "== 6/7 组装 APK =="
cp "$OUT/resources.ap_" "$OUT/unsigned.apk"
(cd "$OUT" && zip -q -j unsigned.apk dex/classes.dex)

echo "== 7/7 zipalign + apksigner 签名 =="
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -alias androiddebugkey \
    -storepass android -keypass android \
    -dname "CN=Android Debug,O=Android,C=US" \
    -keyalg RSA -keysize 2048 -validity 10000
fi
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out app-debug.apk "$OUT/aligned.apk"

ls -lh app-debug.apk
echo "✅ 构建完成（手动工具链，未使用 Gradle）"
