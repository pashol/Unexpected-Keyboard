package com.android.inputmethod.latin.utils;

public final class BinaryDictionaryUtils
{
  static { JniUtils.load_native_library(); }

  private BinaryDictionaryUtils() {}

  private static native boolean createEmptyDictFileNative(String filePath, long dictVersion,
      String locale, String[] attributeKeys, String[] attributeValues);
  private static native float calcNormalizedScoreNative(int[] before, int[] after, int score);
  private static native int setCurrentTimeForTestNative(int currentTime);
}
