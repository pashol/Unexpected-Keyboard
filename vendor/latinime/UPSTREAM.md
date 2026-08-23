# AOSP LatinIME native runtime

This directory vendors the runtime native/JNI source closure for `jni_latinime` from
[AOSP LatinIME](https://android.googlesource.com/platform/packages/inputmethods/LatinIME).

Upstream source URL:
`https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/8081a1d8572f78488900438a6eaaec232b882bbf`

- Commit: `8081a1d8572f78488900438a6eaaec232b882bbf`
- Tag: `android-15.0.0_r1` (one of several upstream release tags pointing at this commit)
- License: Apache License 2.0

The upstream `NOTICE` is retained as `NOTICE`, and each copied source file retains
its Apache copyright and license header.

Copied upstream paths:

- `NOTICE`
- `native/jni/com_android_inputmethod_keyboard_ProximityInfo.{cpp,h}`
- `native/jni/com_android_inputmethod_latin_BinaryDictionary.{cpp,h}`
- `native/jni/com_android_inputmethod_latin_BinaryDictionaryUtils.{cpp,h}`
- `native/jni/com_android_inputmethod_latin_DicTraverseSession.{cpp,h}`
- `native/jni/jni_common.{cpp,h}`
- `native/jni/src/`

Local modifications and build adaptations:

- Upstream paths are relocated under `vendor/latinime/jni/`; source contents are
  otherwise unmodified.
- `native-sources.mk` replaces the upstream Soong `LATIN_IME_CORE_SRC_FILES`
  filegroup with an explicit `ndk-build` list of the five JNI entrypoints and 82
  runtime C++ sources. It does not use recursive globs.
- `../Android.mk` adds the shared `jni_latinime` module, includes
  `jni/src`, uses `-std=c++14`, sets `LOCAL_SDK_VERSION := 21`, and links with
  `-Wl,-z,max-page-size=16384` and `-Wl,--build-id=none`.
- `../Application.mk` sets `APP_PLATFORM := android-21` and
  `APP_STL := c++_static`.

No Java facade or application integration is included in this import.

## Arm64 verification

Run from the repository root with Android NDK `27.0.12077973` installed below
`$ANDROID_SDK_ROOT/ndk` (or set `NDK` to its `ndk-build` executable):

```sh
NDK="${NDK:-${ANDROID_SDK_ROOT:?}/ndk/27.0.12077973/ndk-build}"
READELF="$(dirname "$NDK")/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
OUT=/tmp/latinime-ndk-verify
LIBS=/tmp/latinime-ndk-libs-verify
rm -rf "$OUT" "$LIBS"
"$NDK" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=vendor/Android.mk \
  NDK_APPLICATION_MK=vendor/Application.mk APP_ABI=arm64-v8a \
  NDK_OUT="$OUT" NDK_LIBS_OUT="$LIBS" -j2
LIB="$LIBS/arm64-v8a/libjni_latinime.so"
test -f "$LIB"
"$READELF" -l "$LIB" | grep -E 'LOAD.*0x4000'
! "$READELF" -n "$LIB" | grep -q 'Build ID'
```

The expected linked output is
`/tmp/latinime-ndk-libs-verify/arm64-v8a/libjni_latinime.so`. The `LOAD` program
headers must show `0x4000` alignment (16K pages), and notes must contain no GNU
`Build ID`. The outputs remain in `/tmp` and must not be committed.
