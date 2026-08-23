# AOSP LatinIME native runtime

This directory vendors the runtime native/JNI source closure for `jni_latinime` from
[AOSP LatinIME](https://android.googlesource.com/platform/packages/inputmethods/LatinIME),

License: Apache License 2.0. The upstream `NOTICE` is retained as `NOTICE`, and each
copied source file retains its Apache copyright and license header.

Copied upstream paths:

- `NOTICE`
- `native/jni/com_android_inputmethod_keyboard_ProximityInfo.{cpp,h}`
- `native/jni/com_android_inputmethod_latin_BinaryDictionary.{cpp,h}`
- `native/jni/com_android_inputmethod_latin_BinaryDictionaryUtils.{cpp,h}`
- `native/jni/com_android_inputmethod_latin_DicTraverseSession.{cpp,h}`
- `native/jni/jni_common.{cpp,h}`
- `native/jni/src/`

Local build adaptations:

- `native-sources.mk` replaces the upstream Soong source filegroup with an explicit
  ndk-build source list.
- `../Android.mk` defines the shared `jni_latinime` module with C++14, API 21, 16K
  maximum page size, and no ELF build ID.
- `../Application.mk` selects API 21 and static libc++ (`c++_static`).

No Java facade or application integration is included in this import.
