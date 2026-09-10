#!/usr/bin/env bash
# Builds DoomCam.apk. Run ./setup.sh once first.
set -euo pipefail
cd "$(dirname "$0")"

TC="$PWD/toolchain"
NDK="${ANDROID_NDK:-$TC/android-ndk-r16b}"
BT="${BUILD_TOOLS:-$TC/build-tools}"
JAR="${PLATFORM_JAR:-$TC/platform/android.jar}"
PKG=com.doomcam
MINSDK=9

# Prefer the JDK unpacked into toolchain/; fall back to a system one.
# Fedora 44 no longer ships java-17-openjdk, and 'alternatives' may point
# at a JDK too new for build-tools 30.0.3, so pin it explicitly.
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME=$(ls -d "$TC"/jdk-17* 2>/dev/null | head -1)
  [ -x "${JAVA_HOME:-}/bin/javac" ] || \
    JAVA_HOME=$(ls -d /usr/lib/jvm/java-1[78]-openjdk* /usr/lib/jvm/java-2*-openjdk* 2>/dev/null | head -1)
fi
[ -x "${JAVA_HOME:-}/bin/javac" ] || {
  echo "ERROR: no JDK found. Unpack one into toolchain/, e.g.:"
  echo "  curl -fL -o toolchain/jdk17.tar.gz https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.16+8/OpenJDK17U-jdk_x64_linux_hotspot_17.0.16_8.tar.gz"
  echo "  tar xzf toolchain/jdk17.tar.gz -C toolchain"
  exit 1
}
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

true
[ -x "$NDK/ndk-build" ] || { echo "ERROR: NDK not found at $NDK"; exit 1; }

echo "using JDK: $("$JAVA_HOME/bin/javac" -version 2>&1)"

rm -rf build && mkdir -p build/gen build/classes build/dex build/apk

echo "== 1/6 native (ndk-build) =="
"$NDK/ndk-build" NDK_PROJECT_PATH=. NDK_APPLICATION_MK=jni/Application.mk \
                 NDK_LIBS_OUT=build/libs NDK_OUT=build/obj -j"$(nproc)"

echo "== 2/6 resources (aapt) =="
"$BT/aapt" package -f -m \
  -M AndroidManifest.xml -S res -A assets -I "$JAR" \
  -J build/gen -F build/app.ap_

echo "== 3/6 java (javac) =="
find src build/gen -name '*.java' > build/sources.txt
javac -nowarn -Xlint:-options -encoding UTF-8 -source 1.8 -target 1.8 \
      -bootclasspath "$JAR" -classpath "$JAR" \
      -d build/classes @build/sources.txt

echo "== 4/6 dex (d8) =="
find build/classes -name '*.class' > build/classes.txt
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
     --min-api "$MINSDK" --lib "$JAR" --output build/dex @build/classes.txt

echo "== 5/6 package =="
cp build/app.ap_ build/DoomCam.unsigned.apk
cp build/dex/classes.dex build/apk/
mkdir -p build/apk/lib
cp -r build/libs/armeabi-v7a build/apk/lib/
( cd build/apk && "$BT/aapt" add -f ../DoomCam.unsigned.apk \
    classes.dex lib/armeabi-v7a/*.so >/dev/null )

echo "== 6/6 align + sign =="
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore \
  -storepass android -keypass android -alias doomcam -keyalg RSA -keysize 2048 \
  -validity 10000 -dname "CN=DoomCam, O=Local, C=TR"
"$BT/zipalign" -f -p 4 build/DoomCam.unsigned.apk build/DoomCam.aligned.apk
# v1 signing ONLY, and min-sdk 9 so apksigner picks SHA-1 digests.
# Android 2.3 cannot verify SHA-256 JAR signatures -- get this wrong and the
# camera silently refuses the app.
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version "$MINSDK" --max-sdk-version 30 \
  --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false \
  --out DoomCam.apk build/DoomCam.aligned.apk

echo
echo "Built: $PWD/DoomCam.apk"
