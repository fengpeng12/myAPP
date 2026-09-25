#!/usr/bin/env bash
# Gradle 模式：Android Gradle Plugin(AGP) 构建，自动解析传递依赖 + 合并 AAR 资源。
set -eo pipefail

echo "== 构建方式：Gradle（AGP 8.5.2）=="

MF=app/src/main/AndroidManifest.xml
if [ ! -f "$MF" ]; then echo "缺少 $MF"; exit 1; fi

# 1) 包名：从 manifest 读取（AGP 8 要求写在 namespace，所以稍后会从 manifest 里删掉该属性）
PKG="$(grep -o 'package="[^"]*"' "$MF" | head -1 | sed 's/package="//; s/"$//' || true)"
[ -z "$PKG" ] && PKG="com.example.hello"
echo "namespace = $PKG"

# 2) 版本信息
VCODE=1; VNAME="1.0"
if [ -f appinfo.properties ]; then
  VCODE="$(grep -E '^versionCode=' appinfo.properties | head -1 | cut -d= -f2 | tr -d ' \r' || true)"
  VNAME="$(grep -E '^versionName=' appinfo.properties | head -1 | cut -d= -f2 | tr -d ' \r' || true)"
fi
[ -z "$VCODE" ] && VCODE=1
[ -z "$VNAME" ] && VNAME="1.0"
echo "version = $VNAME ($VCODE)"

# 3) deps.txt → implementation("坐标")
DEPS=""
if [ -f deps.txt ]; then
  while IFS= read -r coord || [ -n "$coord" ]; do
    coord="$(printf '%s' "$coord" | sed 's/#.*//' | tr -d ' \r')"
    [ -z "$coord" ] && continue
    DEPS="$DEPS    implementation(\"$coord\")
"
  done < deps.txt
fi
echo "依赖："
printf '%s' "$DEPS"

# 4) 出现 Compose 依赖时自动开启 Compose 支持
COMPOSE_BLOCK=""
case "$DEPS" in
  *androidx.compose*|*activity-compose*)
    COMPOSE_BLOCK='    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

'
    ;;
esac

# 5) 生成 Gradle 工程文件（工作区里若已有同名文件则保留，便于自定义）
if [ ! -f settings.gradle.kts ]; then
  cat > settings.gradle.kts <<'WEBONLY_EOF'
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "webnex-app"
include(":app")
WEBONLY_EOF
fi

if [ ! -f build.gradle.kts ]; then
  cat > build.gradle.kts <<'WEBONLY_EOF'
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
WEBONLY_EOF
fi

if [ ! -f gradle.properties ]; then
  cat > gradle.properties <<'WEBONLY_EOF'
android.useAndroidX=true
android.nonTransitiveRClass=true
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
kotlin.code.style=official
WEBONLY_EOF
fi

if [ ! -f app/build.gradle.kts ]; then
  cat > app/build.gradle.kts <<WEBONLY_EOF
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "$PKG"
    compileSdk = 34

    defaultConfig {
        applicationId = "$PKG"
        minSdk = 21
        targetSdk = 34
        versionCode = $VCODE
        versionName = "$VNAME"
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

$COMPOSE_BLOCK    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
$DEPS}
WEBONLY_EOF
fi
# 6) AGP 8 不再接受 manifest 里的 package 属性（改用 build.gradle.kts 的 namespace）
sed -i 's/[[:space:]]*package="[^"]*"//' "$MF"

# 7) 构建
gradle assembleDebug --no-daemon --stacktrace

cp app/build/outputs/apk/debug/app-debug.apk app-debug.apk
ls -lh app-debug.apk
echo "构建完成（Gradle 模式）"
