#!/bin/sh
set -eu

NDK="${NDK:-${ANDROID_SDK_ROOT:?}/ndk/27.0.12077973/ndk-build}"
READELF="$(dirname "$NDK")/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
OUT="${OUT:-/tmp/latinime-ndk-verify}"
LIBS="${LIBS:-/tmp/latinime-ndk-libs-verify}"
APP_ABI="armeabi-v7a arm64-v8a x86 x86_64"

rm -rf "$OUT" "$LIBS"
"$NDK" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=vendor/Android.mk \
  NDK_APPLICATION_MK=vendor/Application.mk APP_ABI="$APP_ABI" \
  NDK_OUT="$OUT" NDK_LIBS_OUT="$LIBS" -j2

for abi in $APP_ABI; do
  lib="$LIBS/$abi/libjni_latinime.so"
  test -f "$lib"
  "$READELF" -lW "$lib" | awk '$1 == "LOAD" {
    loads++
    if ($NF != "0x4000") {
      printf "%s: LOAD alignment is %s, expected 0x4000\n", FILENAME, $NF > "/dev/stderr"
      exit 1
    }
  }
  END {
    if (loads == 0) {
      printf "%s: no LOAD segments found\n", FILENAME > "/dev/stderr"
      exit 1
    }
  }'
  ! "$READELF" -n "$lib" | grep -q 'Build ID:'
done
