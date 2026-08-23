/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.inputmethod.latin;

/** Explicitly-owned traversal state for typed and empty-input suggestions. */
public final class DicTraverseSession implements AutoCloseable
{
  private long handle;

  public DicTraverseSession(String locale, long dictionarySize)
  {
    handle = setDicTraverseSessionNative(locale, dictionarySize);
  }

  public void initialize(long dictionary, int[] previousWord, int previousWordLength)
  {
    initDicTraverseSessionNative(handle, dictionary, previousWord, previousWordLength);
  }

  public long handle() { return handle; }

  public void close()
  {
    if (handle != 0) {
      releaseDicTraverseSessionNative(handle);
      handle = 0;
    }
  }

  private static native long setDicTraverseSessionNative(String locale, long dictionarySize);
  private static native void initDicTraverseSessionNative(long session, long dictionary,
      int[] previousWord, int previousWordLength);
  private static native void releaseDicTraverseSessionNative(long session);
}
