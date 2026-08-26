package com.android.inputmethod.latin.utils;

public final class JniUtils
{
  private JniUtils() {}

  public static void load_native_library()
  {
    System.loadLibrary("jni_latinime");
  }
}
