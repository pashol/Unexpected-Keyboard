#!/bin/sh
set -eu

APP_ABI="armeabi-v7a arm64-v8a x86 x86_64"

if [ -z "${READELF:-}" ]; then
  NDK="${NDK:-${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT or NDK to the required NDK 27.0.12077973 ndk-build executable}/ndk/27.0.12077973/ndk-build}"
  READELF="$(dirname "$NDK")/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
fi

if [ -z "${LIBS:-}" ]; then
  if [ -z "${APK:-}" ]; then
    set -- build/outputs/apk/release/*.apk
    if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
      printf '%s\n' "Expected exactly one assembled release APK under build/outputs/apk/release" >&2
      exit 1
    fi
    APK="$1"
  fi
  work="$(mktemp -d)"
  trap 'rm -rf "$work"' EXIT
  unzip -qq "$APK" 'lib/*/libjni_latinime.so' -d "$work"
  LIBS="$work/lib"
fi

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
