package com.android.inputmethod.latin;

import com.android.inputmethod.latin.utils.JniUtils;

public final class DicTraverseSession implements AutoCloseable
{
  static { JniUtils.load_native_library(); }

  private long _native_session;

  public DicTraverseSession(String locale, long dictionary, long dictionarySize)
  {
    _native_session = setDicTraverseSessionNative(locale, dictionarySize);
    initDicTraverseSessionNative(_native_session, dictionary, null, 0);
  }

  public long native_session()
  {
    return _native_session;
  }

  @Override public void close()
  {
    if (_native_session != 0)
    {
      releaseDicTraverseSessionNative(_native_session);
      _native_session = 0;
    }
  }

  private static native long setDicTraverseSessionNative(String locale, long dictionarySize);
  private static native void initDicTraverseSessionNative(long session, long dictionary,
      int[] previousWord, int previousWordLength);
  private static native void releaseDicTraverseSessionNative(long session);
}
