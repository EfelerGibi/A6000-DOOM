#!/usr/bin/env bash
# Pushes DoomCam.apk to the camera. Camera must be ON, USB Connection = Mass Storage,
# card inserted, screen showing "USB Mode".
set -euo pipefail
cd "$(dirname "$0")"
TC="$PWD/toolchain"
APK="${1:-$PWD/DoomCam.apk}"

[ -f "$APK" ] || { echo "no such apk: $APK"; exit 1; }

# GNOME auto-mounts the camera; libusb then can't claim it. Unmount first.
for d in /dev/disk/by-id/usb-Sony*; do
  [ -e "$d" ] && udisksctl unmount -b "$(readlink -f "$d")" 2>/dev/null || true
done

cd "$TC/Sony-PMCA-RE"
sudo "$TC/venv/bin/python" pmca-console.py install -f "$APK"
