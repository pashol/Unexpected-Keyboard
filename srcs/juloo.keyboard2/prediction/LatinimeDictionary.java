package juloo.keyboard2.prediction;

import com.android.inputmethod.latin.BinaryDictionary;
import com.android.inputmethod.latin.DicTraverseSession;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class LatinimeDictionary implements AutoCloseable
{
  private static final int FORMAT_VERSION = 202;
  private static final int MAX_WORD_LENGTH = 48;
  private static final int NATIVE_MAX_RESULTS = 18;

  private final NativeDecoderLifecycle _lifecycle = new NativeDecoderLifecycle();
  private final long _dictionary;
  private final DicTraverseSession _session;

  private LatinimeDictionary(File file, long dictionary)
  {
    _dictionary = dictionary;
    _session = new DicTraverseSession("en", dictionary, file.length());
  }

  public static LatinimeDictionary open(File file) throws IOException
  {
    if (!file.isFile() || file.length() == 0)
      throw new IOException("Dictionary file is missing or empty");
    long dictionary = BinaryDictionary.open(file.getAbsolutePath(), file.length());
    if (dictionary == 0)
      throw new IOException("Unable to open dictionary");
    if (BinaryDictionary.format_version(dictionary) != FORMAT_VERSION
        || BinaryDictionary.is_corrupted(dictionary))
    {
      BinaryDictionary.close(dictionary);
      throw new IOException("Dictionary is not a valid format-202 dictionary");
    }
    return new LatinimeDictionary(file, dictionary);
  }

  public List<PredictionCandidate> next_words(List<String> preceding_words, int max_candidates)
  {
    if (!_lifecycle.is_open())
      throw new IllegalStateException("Dictionary is closed");
    if (max_candidates <= 0)
      throw new IllegalArgumentException("max_candidates");
    int result_count = Math.min(max_candidates, NATIVE_MAX_RESULTS);
    int[] count = new int[1];
    int[] code_points = new int[NATIVE_MAX_RESULTS * MAX_WORD_LENGTH];
    int[] scores = new int[NATIVE_MAX_RESULTS];
    BinaryDictionary.get_suggestions(_dictionary, _session.native_session(),
        code_points(preceding_words), result_count, count, code_points, scores);
    ArrayList<PredictionCandidate> results = new ArrayList<PredictionCandidate>();
    for (int i = 0; i < Math.min(count[0], result_count); ++i)
    {
      int start = i * MAX_WORD_LENGTH;
      int length = 0;
      while (length < MAX_WORD_LENGTH && code_points[start + length] != 0)
        ++length;
      if (length > 0)
        results.add(new PredictionCandidate(new String(code_points, start, length), scores[i]));
    }
    return results;
  }

  @Override public void close()
  {
    if (_lifecycle.is_open())
    {
      _lifecycle.close();
      _session.close();
      BinaryDictionary.close(_dictionary);
    }
  }

  static int[][] code_points(List<String> words)
  {
    int size = Math.min(3, words.size());
    int[][] result = new int[size][];
    for (int i = 0; i < size; ++i)
      result[i] = code_points(words.get(words.size() - 1 - i));
    return result;
  }

  private static int[] code_points(String word)
  {
    int[] result = new int[word.codePointCount(0, word.length())];
    for (int offset = 0, index = 0; offset < word.length(); ++index)
    {
      int code_point = word.codePointAt(offset);
      result[index] = code_point;
      offset += Character.charCount(code_point);
    }
    return result;
  }
}
