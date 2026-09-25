#!/usr/bin/env bash
# 最小 Android 工具链构建：依赖下载 → aapt2 → kotlinc → javac → d8 → zipalign → apksigner
set -eo pipefail

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then SDK="/usr/local/lib/android/sdk"; fi
if [ ! -d "$SDK" ]; then echo "❌ 找不到 Android SDK"; exit 1; fi
echo "SDK = $SDK"

if [ ! -d "$SDK/build-tools" ]; then echo "❌ 缺少 build-tools"; exit 1; fi
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
echo "build-tools = $BT"

if [ -f "$SDK/platforms/android-34/android.jar" ]; then
  PLATFORM="$SDK/platforms/android-34/android.jar"
else
  PLATFORM="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
fi
if [ -z "$PLATFORM" ] || [ ! -f "$PLATFORM" ]; then echo "❌ 找不到 android.jar"; exit 1; fi
echo "platform = $PLATFORM"

echo "kotlinc = $(which kotlinc || echo 未找到)"
java -version 2>&1 | head -1

OUT="build-manual"
rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/classes" "$OUT/dex" "$OUT/gen" "$OUT/libs"

echo "== 0/7 下载第三方依赖（deps.txt）=="
if [ -f deps.txt ]; then
  while IFS= read -r coord || [ -n "$coord" ]; do
    coord="$(printf '%s' "$coord" | sed 's/#.*//' | tr -d ' \r')"
    [ -z "$coord" ] && continue
    GROUP="$(printf '%s' "$coord" | cut -d: -f1 | tr '.' '/')"
    ART="$(printf '%s' "$coord" | cut -d: -f2)"
    VER="$(printf '%s' "$coord" | cut -d: -f3)"
    BASE="https://repo1.maven.org/maven2/$GROUP/$ART/$VER"
    if curl -sSLf -o "$OUT/libs/$ART-$VER.aar" "$BASE/$ART-$VER.aar" 2>/dev/null; then
      if ( cd "$OUT/libs" && unzip -o -q "$ART-$VER.aar" classes.jar && mv -f classes.jar "$ART-$VER.jar" ); then
        rm -f "$OUT/libs/$ART-$VER.aar"
        echo "   ✔ $coord (aar → jar)"
      else
        echo "   ✘ $coord 解压失败"
      fi
    elif curl -sSLf -o "$OUT/libs/$ART-$VER.jar" "$BASE/$ART-$VER.jar" 2>/dev/null; then
      echo "   ✔ $coord (jar)"
    else
      rm -f "$OUT/libs/$ART-$VER.aar" "$OUT/libs/$ART-$VER.jar"
      echo "   ✘ 下载失败：$coord（检查坐标是否正确）"
    fi
  done < deps.txt
else
  echo "   （无 deps.txt，跳过）"
fi
LIBS="$(find "$OUT/libs" -name '*.jar' 2>/dev/null | tr '\n' ':')"
LIBS="${LIBS%:}"
if [ -n "$LIBS" ]; then CP="$PLATFORM:$LIBS"; else CP="$PLATFORM"; fi
echo "classpath = $CP"

echo "== 1/7 aapt2 compile 资源 =="
"$BT/aapt2" compile --dir app/src/main/res -o "$OUT/compiled/res.zip"

echo "== 2/7 aapt2 link（生成 R.java + resources.ap_）=="
"$BT/aapt2" link -o "$OUT/resources.ap_" -I "$PLATFORM" \
  --manifest app/src/main/AndroidManifest.xml \
  --java "$OUT/gen" \
  --min-sdk-version 21 --target-sdk-version 34 \
  "$OUT/compiled/res.zip"

echo "== 3/7 kotlinc 编译 Kotlin =="
kotlinc app/src/main/kotlin -classpath "$CP" -jvm-target 17 -d "$OUT/classes"

echo "== 4/7 javac 编译 R.java =="
JAVAS="$(find "$OUT/gen" -name '*.java' 2>/dev/null || true)"
if [ -n "$JAVAS" ]; then
  javac -classpath "$CP" -d "$OUT/classes" $JAVAS
else
  echo "   （未生成 R.java，跳过）"
fi

echo "== 5/7 d8 转 dex（含 kotlin-stdlib 与第三方 jar）=="
KOTLIN_HOME="$(dirname "$(which kotlinc)")/.."
STDLIB="$KOTLIN_HOME/lib/kotlin-stdlib.jar"
echo "stdlib = $STDLIB"
"$BT/d8" --lib "$PLATFORM" --min-api 21 --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class') $(find "$OUT/libs" -name '*.jar') "$STDLIB"

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
