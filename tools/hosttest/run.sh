#!/usr/bin/env bash
# Desktop check of the frame path, no camera or NDK needed:
#   1. new path (CMAP256 + dc_core.c) vs the previous release's path must
#      produce byte-identical RGB565 frames for the same demo gametics;
#   2. the threaded handoff runs under ThreadSanitizer without reports.
# Needs gcc (with libtsan for step 2), python3, jni/doomgeneric from ./setup.sh, and an
# IWAD with a DEMO1 lump:  tools/hosttest/run.sh /path/to/freedoom1.wad
set -euo pipefail
cd "$(dirname "$0")/../.."

IWAD="${1:?usage: $0 <iwad> [frames]}"
N="${2:-600}"
OUT="${OUT:-build/hosttest}"
DG=jni/doomgeneric
[ -d "$DG" ] || { echo "run ./setup.sh first (needs $DG)"; exit 1; }
mkdir -p "$OUT"

SRC=$(sed -n '/^DG_SRC :=/,/^$/p' jni/Android.mk | sed 's/DG_SRC :=//; s/\\//g')
SRC=$(for f in $SRC; do echo "$DG/$f"; done)
FLAGS="-DNORMALUNIX -DLINUX -DDOOMGENERIC_RESX=320 -DDOOMGENERIC_RESY=200 -O2 -w -I jni -I $DG"

echo "== build: reference (32-bit doomgeneric + old blit)"
gcc $FLAGS -o "$OUT/ref" tools/hosttest/hosttest.c $SRC -lm
echo "== build: new (CMAP256 + dc_core.c)"
gcc $FLAGS -DCMAP256 -o "$OUT/new" tools/hosttest/hosttest.c jni/dc_core.c $SRC -lm -lpthread

echo "== $N demo frames each"
"$OUT/ref" frames "$IWAD" "$N" "$OUT/ref.raw" >/dev/null
"$OUT/new" frames "$IWAD" "$N" "$OUT/new.raw" >/dev/null
if python3 tools/hosttest/cmpframes.py "$OUT/ref.raw" "$OUT/new.raw"; then
  echo "PASS: new path is byte-identical to the previous release"
else
  echo "FAIL: frames differ"; exit 1
fi

echo "== threaded handoff under ThreadSanitizer"
if gcc $FLAGS -DCMAP256 -g -fsanitize=thread -o "$OUT/new_tsan" \
       tools/hosttest/hosttest.c jni/dc_core.c $SRC -lm -lpthread; then
  for mode in mailbox fifo; do
    for hz in 30 60; do
      TSAN_OPTIONS="halt_on_error=1" "$OUT/new_tsan" threads "$IWAD" 4 $mode $hz 2>"$OUT/tsan_$mode$hz.log" \
        | grep -v '^[A-Z_]*:' || { echo "FAIL: see $OUT/tsan_$mode$hz.log"; exit 1; }
    done
  done
  echo "PASS: no ThreadSanitizer reports"
else
  echo "SKIP: gcc can't build with -fsanitize=thread here"
fi
