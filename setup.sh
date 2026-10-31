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
# Pinned: dc_core.c reads doomgeneric's CMAP256 internals (colors[],
# palette_changed), so an unreviewed upstream change could break the build.
DG_COMMIT=e257a09527062bfb986b6e5e0780839b6cbd5b71
if [ ! -d jni/doomgeneric ]; then
  echo ">> fetching doomgeneric @ ${DG_COMMIT:0:7}"
  rm -rf "$TC/doomgeneric-src"
  git init -q "$TC/doomgeneric-src"
  git -C "$TC/doomgeneric-src" fetch -q --depth 1 https://github.com/ozkl/doomgeneric.git "$DG_COMMIT"
  git -C "$TC/doomgeneric-src" checkout -q FETCH_HEAD
  cp -r "$TC/doomgeneric-src/doomgeneric" jni/doomgeneric
  # Freedoom Phase 1 has four episodes but no M_EPI4 menu graphic; without
  # this Doom aborts with "W_GetNumForName: M_EPI4 not found!".
  sed -i 's|    if (gameversion < exe_ultimate)|    if (gameversion < exe_ultimate \|\| W_CheckNumForName("M_EPI4") < 0)|' \
      jni/doomgeneric/m_menu.c
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
