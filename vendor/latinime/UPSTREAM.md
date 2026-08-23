# AOSP LatinIME Decoder

- Source: https://android.googlesource.com/platform/packages/inputmethods/LatinIME
- Tag: android-15.0.0_r1
- Commit: 8081a1d8572f78488900438a6eaaec232b882bbf
- License: Apache License 2.0; see `NOTICE` and retained file headers.

## Local Modifications

- The complete upstream `native/jni/src` decoder closure and the JNI dictionary,
  traversal-session, proximity, and common bridges are vendored unchanged except
  that JNI registration is restricted to the Build 1 surface.
- `native-sources.mk` explicitly expands the 82 upstream
  `LATIN_IME_CORE_SRC_FILES` entries from `native/jni/Android.bp`.
- Java is reduced to explicit-close bindings for dictionary open/validation/close,
  traversal sessions, proximity geometry, and typed or empty-input suggestions.
  Upstream finalizers are not retained.
