package com.android.inputmethod.latin;

import com.android.inputmethod.latin.utils.JniUtils;
import com.android.inputmethod.latin.utils.WordInputEventForPersonalization;
import java.util.ArrayList;

public final class BinaryDictionary
{
  static { JniUtils.load_native_library(); }

  private BinaryDictionary() {}

  public static long open(String path, long length)
  {
    return openNative(path, 0, length, false);
  }

  public static void close(long dictionary)
  {
    if (dictionary != 0)
      closeNative(dictionary);
  }

  public static int format_version(long dictionary)
  {
    return getFormatVersionNative(dictionary);
  }

  public static boolean is_corrupted(long dictionary)
  {
    return isCorruptedNative(dictionary);
  }

  public static void get_suggestions(long dictionary, long session, int[][] precedingWords,
      int maxCandidates, int[] count, int[] codePoints, int[] scores)
  {
    getSuggestionsNative(dictionary, 0, session, new int[0], new int[0], new int[0], new int[0],
        new int[0], 0, new int[5], precedingWords, new boolean[precedingWords.length],
        precedingWords.length, count, codePoints, scores, new int[18], new int[18], new int[1],
        new float[1]);
  }

  private static native long openNative(String path, long offset, long length, boolean writable);
  private static native long createOnMemoryNative(long formatVersion, String locale,
      String[] attributeKeys, String[] attributeValues);
  private static native void closeNative(long dictionary);
  private static native int getFormatVersionNative(long dictionary);
  private static native void getHeaderInfoNative(long dictionary, int[] headerSize,
      int[] formatVersion, ArrayList<int[]> attributeKeys, ArrayList<int[]> attributeValues);
  private static native boolean flushNative(long dictionary, String path);
  private static native boolean needsToRunGCNative(long dictionary, boolean mindsBlockByGC);
  private static native boolean flushWithGCNative(long dictionary, String path);
  private static native void getSuggestionsNative(long dictionary, long proximityInfo, long session,
      int[] xCoordinates, int[] yCoordinates, int[] times, int[] pointerIds, int[] inputCodePoints,
      int inputSize, int[] suggestOptions, int[][] previousWords, boolean[] beginningOfSentence,
      int previousWordCount, int[] outputCount, int[] outputCodePoints, int[] outputScores,
      int[] outputIndices, int[] outputTypes, int[] outputAutoCommitConfidence, float[] weight);
  private static native int getProbabilityNative(long dictionary, int[] word);
  private static native int getMaxProbabilityOfExactMatchesNative(long dictionary, int[] word);
  private static native int getNgramProbabilityNative(long dictionary, int[][] previousWords,
      boolean[] beginningOfSentence, int[] word);
  private static native void getWordPropertyNative(long dictionary, int[] word,
      boolean beginningOfSentence, int[] codePoints, boolean[] flags, int[] probabilityInfo,
      ArrayList<int[][]> ngramPreviousWords, ArrayList<boolean[]> ngramBeginningOfSentence,
      ArrayList<int[]> ngramTargets, ArrayList<int[]> ngramProbabilityInfo,
      ArrayList<int[]> shortcutTargets, ArrayList<Integer> shortcutProbabilities);
  private static native int getNextWordNative(long dictionary, int token, int[] codePoints,
      boolean[] beginningOfSentence);
  private static native boolean addUnigramEntryNative(long dictionary, int[] word, int probability,
      int[] shortcutTarget, int shortcutProbability, boolean beginningOfSentence, boolean notAWord,
      boolean possiblyOffensive, int timestamp);
  private static native boolean removeUnigramEntryNative(long dictionary, int[] word);
  private static native boolean addNgramEntryNative(long dictionary, int[][] previousWords,
      boolean[] beginningOfSentence, int[] word, int probability, int timestamp);
  private static native boolean removeNgramEntryNative(long dictionary, int[][] previousWords,
      boolean[] beginningOfSentence, int[] word);
  private static native boolean updateEntriesForWordWithNgramContextNative(long dictionary,
      int[][] previousWords, boolean[] beginningOfSentence, int[] word, boolean validWord,
      int count, int timestamp);
  private static native int updateEntriesForInputEventsNative(long dictionary,
      WordInputEventForPersonalization[] inputEvents, int startIndex);
  private static native String getPropertyNative(long dictionary, String query);
  private static native boolean isCorruptedNative(long dictionary);
  private static native boolean migrateNative(long dictionary, String path, long formatVersion);
}
