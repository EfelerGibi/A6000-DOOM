#!/usr/bin/env bash
# One-time setup. Downloads everything into ./toolchain (nothing goes into /usr).
# Total download: ~1.0 GB (the NDK is most of it).
set -euo pipefail
cd "$(dirname "$0")"

TC="$PWD/toolchain"
mkdir -p "$TC"

fetch() { # url outfile
  [ -f "$2" ] && { echo "already have $2"; return; }
  echo ">> downloading $1"
  curl -fL --progress-bar -o "$2.part" "$1"
  mv "$2.part" "$2"
}

# ---- 1. Android NDK r16b (last NDK with the GCC toolchain Android 2.3 needs)
if [ ! -d "$TC/android-ndk-r16b" ]; then
  fetch https://dl.google.com/android/repository/android-ndk-r16b-linux-x86_64.zip "$TC/ndk.zip"
  echo ">> unpacking NDK (slow, ~3 GB on disk)"
  unzip -q "$TC/ndk.zip" -d "$TC"
  rm -f "$TC/ndk.zip"
fi

# ---- 2. SDK build-tools 30.0.3 (aapt, d8, zipalign, apksigner)
if [ ! -d "$TC/build-tools" ]; then
  fetch https://dl.google.com/android/repository/build-tools_r30.0.3-linux.zip "$TC/bt.zip"
  unzip -q "$TC/bt.zip" -d "$TC/bt-tmp"
  mv "$TC"/bt-tmp/* "$TC/build-tools"
  rmdir "$TC/bt-tmp"; rm -f "$TC/bt.zip"
fi

# ---- 3. android.jar (API 28) to compile against
mkdir -p "$TC/platform"
fetch https://raw.githubusercontent.com/Sable/android-platforms/master/android-28/android.jar \
      "$TC/platform/android.jar"

# ---- 4. doomgeneric sources, straight into jni/ so ndk-build sees them
if [ ! -d jni/doomgeneric ]; then
  echo ">> cloning doomgeneric"
  git clone --depth 1 https://github.com/ozkl/doomgeneric.git "$TC/doomgeneric-src"
  cp -r "$TC/doomgeneric-src/doomgeneric" jni/doomgeneric
fi

# ---- 5. Sony-PMCA-RE + its python venv
if [ ! -d "$TC/Sony-PMCA-RE" ]; then
  git clone --depth 1 https://github.com/ma1co/Sony-PMCA-RE.git "$TC/Sony-PMCA-RE"
fi
if [ ! -d "$TC/venv" ]; then
  python3 -m venv "$TC/venv"
  # pyinstaller is only needed to build binaries; skip it to avoid churn
  "$TC/venv/bin/pip" -q install --upgrade pip
  "$TC/venv/bin/pip" -q install pyusb pycryptodomex tlslite-ng pyyaml asn1crypto certifi axmlparserpy==0.0.4
fi

echo
echo "Setup done."
echo "Now drop a DOOM1.WAD into $PWD/assets/ and run ./build.sh"
