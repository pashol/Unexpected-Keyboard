/*
 * Copyright (C) 2008 The Android Open Source Project
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

/** Minimal JNI surface for immutable binary dictionaries. */
public final class BinaryDictionary
{
  static { System.loadLibrary("jni_latinime"); }

  private BinaryDictionary() {}

  public static long open(String path, long offset, long length)
  {
    return openNative(path, offset, length, false);
  }

  public static void close(long dictionary) { closeNative(dictionary); }
  public static boolean validate(long dictionary) { return validateNative(dictionary); }

  private static native long openNative(String path, long offset, long length,
      boolean isUpdatable);
  private static native void closeNative(long dictionary);
  private static native boolean validateNative(long dictionary);
   public static native void getSuggestionsNative(long dictionary, long proximityInfo,
      long traverseSession, int[] xCoordinates, int[] yCoordinates, int[] times,
      int[] pointerIds, int[] inputCodePoints, int inputSize, int[] suggestOptions,
      int[][] previousWords, boolean[] beginningOfSentence, int previousWordCount,
      int[] outputSuggestionCount, int[] outputCodePoints, int[] outputScores,
       int[] outputIndices, int[] outputTypes, int[] autoCommitConfidence,
       float[] languageModelWeight);
   public static native int getProbabilityNative(long dictionary, int[] word);
   public static native int getNgramProbabilityNative(long dictionary, int[][] previousWords,
       boolean[] beginningOfSentence, int[] word);
}
