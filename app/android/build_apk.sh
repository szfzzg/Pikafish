#!/usr/bin/env bash
# 构建 Pikafish 象棋 Android APK（arm64-v8a）。
# 依赖：Android SDK (platform 34, build-tools 34.0.0, NDK 26.1.10909125)、JDK 17+。
set -euo pipefail

# build-tools 34 的 d8 需要 JDK 17；若系统安装了则优先使用。
if [ -x /usr/lib/jvm/java-17-openjdk-amd64/bin/javac ]; then
    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
    export PATH="$JAVA_HOME/bin:$PATH"
fi

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$APP_DIR/../.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
BT_VERSION="34.0.0"
NDK_VERSION="26.1.10909125"
API=24
TARGET_API=34

BT="$SDK/build-tools/$BT_VERSION"
NDK="$SDK/ndk/$NDK_VERSION"
PLATFORM="$SDK/platforms/android-$TARGET_API"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CXX="$TOOLCHAIN/aarch64-linux-android${API}-clang++"
STRIP="$TOOLCHAIN/llvm-strip"
BUILD="$APP_DIR/build"
APK="$APP_DIR/../PikafishXiangqi.apk"

for path in "$BT/aapt2" "$PLATFORM/android.jar" "$CXX"; do
    if [ ! -e "$path" ]; then
        echo "缺少构建组件：$path" >&2
        echo "请先安装：platforms;android-$TARGET_API build-tools;$BT_VERSION ndk;$NDK_VERSION" >&2
        exit 1
    fi
done

if [ ! -f "$REPO_DIR/src/pikafish.nnue" ]; then
    echo "缺少 NNUE 网络：$REPO_DIR/src/pikafish.nnue" >&2
    exit 1
fi

rm -rf "$BUILD"
mkdir -p "$BUILD/obj" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" \
         "$BUILD/assets" "$BUILD/stage/lib/arm64-v8a"

echo "==> 编译 arm64 引擎"
SOURCES=(
    main.cpp benchmark.cpp bitboard.cpp evaluate.cpp misc.cpp movegen.cpp
    movepick.cpp position.cpp search.cpp thread.cpp timeman.cpp tt.cpp tune.cpp
    uci.cpp ucioption.cpp
    nnue/evaluate_nnue.cpp
    nnue/features/half_ka_v2_hm.cpp
    external/zip.cpp
)
CXXFLAGS=(
    -std=c++17 -O3 -DNDEBUG -DIS_64BIT -DUSE_PTHREADS -DUSE_NEON=8
    -march=armv8-a -fno-exceptions -fPIE -I"$REPO_DIR/src"
)
ENGINE="$BUILD/libpikafish.so"
PIDS=()
COUNT=0
for src in "${SOURCES[@]}"; do
    obj="$BUILD/obj/$(basename "${src%.cpp}").o"
    "$CXX" "${CXXFLAGS[@]}" -c "$REPO_DIR/src/$src" -o "$obj" &
    PIDS+=($!)
    COUNT=$((COUNT + 1))
    if [ $((COUNT % 3)) -eq 0 ]; then
        for pid in "${PIDS[@]}"; do wait "$pid"; done
        PIDS=()
    fi
done
for pid in "${PIDS[@]}"; do wait "$pid"; done

"$CXX" -pie -o "$ENGINE" "$BUILD"/obj/*.o \
    -static-libstdc++ -pthread -lm -Wl,--no-undefined -Wl,-z,max-page-size=16384
"$STRIP" --strip-unneeded "$ENGINE"
file "$ENGINE"

echo "==> 打包资源与代码"
cp "$REPO_DIR/src/pikafish.nnue" "$BUILD/assets/pikafish.nnue"

"$BT/aapt2" compile --dir "$APP_DIR/res" -o "$BUILD/res.zip"
"$BT/aapt2" link -o "$BUILD/base.apk" \
    -I "$PLATFORM/android.jar" \
    --manifest "$APP_DIR/AndroidManifest.xml" \
    --java "$BUILD/gen" \
    --min-sdk-version "$API" --target-sdk-version "$TARGET_API" \
    -A "$BUILD/assets" \
    "$BUILD/res.zip"

mapfile -t JAVA_SOURCES < <(find "$APP_DIR/src" "$BUILD/gen" -name '*.java')
javac --release 17 -encoding UTF-8 -classpath "$PLATFORM/android.jar" \
    -d "$BUILD/classes" "${JAVA_SOURCES[@]}"

mapfile -t CLASSES < <(find "$BUILD/classes" -name '*.class')
"$BT/d8" --release --min-api "$API" --lib "$PLATFORM/android.jar" \
    --output "$BUILD/dex" "${CLASSES[@]}"

cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
(cd "$BUILD/dex" && zip -q "$BUILD/unsigned.apk" classes.dex)
cp "$ENGINE" "$BUILD/stage/lib/arm64-v8a/libpikafish.so"
(cd "$BUILD/stage" && zip -q "$BUILD/unsigned.apk" lib/arm64-v8a/libpikafish.so)

"$BT/zipalign" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "==> 签名"
KEYSTORE="$APP_DIR/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 \
        -storepass android -keypass android -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Pikafish Xiangqi Debug,O=Android,C=CN"
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android \
    --out "$APK" "$BUILD/aligned.apk"

echo "==> 验证"
"$BT/apksigner" verify --verbose "$APK" | head -6
"$BT/aapt2" dump badging "$APK" | head -8
unzip -l "$APK" | grep -E "classes.dex|assets/pikafish.nnue|lib/arm64-v8a" || true

echo "APK 已生成：$APK"
