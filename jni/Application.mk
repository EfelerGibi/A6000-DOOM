APP_ABI := armeabi-v7a
# NDK r16b's floor is android-14. The manifest still declares minSdkVersion 9;
# Doom only uses libc calls that exist in Gingerbread's bionic, so the mismatch
# is cosmetic. Set explicitly to silence the fallback warning.
APP_PLATFORM := android-14
NDK_TOOLCHAIN_VERSION := 4.9
APP_OPTIM := release
APP_STL := system
