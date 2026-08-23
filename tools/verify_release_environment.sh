#!/bin/sh
set -eu

missing=""
for variable in RELEASE_KEYSTORE RELEASE_KEYSTORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD ANDROID_SDK_ROOT; do
  eval "value=\${$variable:-}"
  if [ -z "$value" ]; then
    missing="${missing}${missing:+, }$variable"
  fi
done

if [ -n "$missing" ]; then
  printf '%s\n' "Release verification requires: $missing" >&2
  exit 1
fi

if [ ! -f "$RELEASE_KEYSTORE" ]; then
  printf '%s\n' "RELEASE_KEYSTORE must name an existing keystore file" >&2
  exit 1
fi

if [ ! -f "$ANDROID_SDK_ROOT/ndk/27.0.12077973/ndk-build" ]; then
  printf '%s\n' "Release verification requires NDK 27.0.12077973 under ANDROID_SDK_ROOT/ndk" >&2
  exit 1
fi
