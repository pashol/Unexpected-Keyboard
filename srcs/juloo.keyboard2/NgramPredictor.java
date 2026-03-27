package juloo.keyboard2;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bigram language model for context-aware next-word prediction.
 *
 * Learns which words the user types after a given word and persists the
 * frequency table to disk. Designed as the personalization layer of a
 * production autocomplete system — the "neural" component that adds sequence
 * awareness on top of the static Cdict trie and UserDictionary word list.
 *
 * Can be replaced by a TFLite LSTM without changing the integration layer
 * (Suggestions.java calls predict() and observe() only).
 *
 * Call {@link #init(Context)} once in Keyboard2.onCreate().
 */
public final class NgramPredictor
{
  private static NgramPredictor _instance;

  private final File _file;
  /** prevWord → (nextWord → observationCount) */
  private final HashMap<String, HashMap<String, Integer>> _bigrams = new HashMap<>();
  private int _dirty_count = 0;
  /** Flush to disk every N observations to limit I/O. */
  private static final int SAVE_INTERVAL = 50;

  private NgramPredictor(File filesDir)
  {
    _file = new File(filesDir, "ngram_data.txt");
    load();
  }

  /** Call once in Keyboard2.onCreate() after UserDictionary.init(). */
  public static void init(Context context)
  {
    if (_instance == null)
      _instance = new NgramPredictor(context.getFilesDir());
  }

  public static NgramPredictor getInstance() { return _instance; }

  /**
   * Record that [next] was typed immediately after [prev].
   * Called by Suggestions.word_committed() when neural suggestions are enabled.
   */
  public void observe(String prev, String next)
  {
    if (prev == null || next == null || prev.isEmpty() || next.isEmpty()) return;
    prev = prev.toLowerCase();
    next = next.toLowerCase();
    HashMap<String, Integer> followers = _bigrams.get(prev);
    if (followers == null)
    {
      followers = new HashMap<>();
      _bigrams.put(prev, followers);
    }
    Integer count = followers.get(next);
    followers.put(next, count == null ? 1 : count + 1);
    if (++_dirty_count >= SAVE_INTERVAL)
    {
      save();
      _dirty_count = 0;
    }
  }

  /**
   * Return up to [topK] words most likely to follow [prev], optionally
   * filtered to words starting with [prefix] (case-insensitive).
   *
   * @param prev    the last committed word (context)
   * @param prefix  optional prefix filter — pass null or "" to get all
   * @param topK    maximum number of results
   * @return sorted list of predicted words (most likely first), may be empty
   */
  public List<String> predict(String prev, String prefix, int topK)
  {
    if (prev == null || topK <= 0) return Collections.emptyList();
    HashMap<String, Integer> followers = _bigrams.get(prev.toLowerCase());
    if (followers == null || followers.isEmpty()) return Collections.emptyList();

    String lowerPrefix = (prefix != null && !prefix.isEmpty())
      ? prefix.toLowerCase() : null;

    List<Map.Entry<String, Integer>> entries = new ArrayList<>();
    for (Map.Entry<String, Integer> e : followers.entrySet())
    {
      if (lowerPrefix == null || e.getKey().startsWith(lowerPrefix))
        entries.add(e);
    }
    Collections.sort(entries, (a, b) -> b.getValue() - a.getValue());

    List<String> result = new ArrayList<>();
    int limit = Math.min(topK, entries.size());
    for (int i = 0; i < limit; i++)
      result.add(entries.get(i).getKey());
    return result;
  }

  /** Flush the bigram table to disk. Called on keyboard destroy and periodically. */
  public void save()
  {
    try
    {
      PrintWriter pw = new PrintWriter(new FileWriter(_file));
      try
      {
        for (Map.Entry<String, HashMap<String, Integer>> e : _bigrams.entrySet())
          for (Map.Entry<String, Integer> f : e.getValue().entrySet())
            pw.println(e.getKey() + "\t" + f.getKey() + "\t" + f.getValue());
      }
      finally { pw.close(); }
    }
    catch (IOException ignored) {}
  }

  private void load()
  {
    if (!_file.exists()) return;
    try
    {
      BufferedReader br = new BufferedReader(new FileReader(_file));
      try
      {
        String line;
        while ((line = br.readLine()) != null)
        {
          String[] parts = line.split("\t");
          if (parts.length != 3) continue;
          try
          {
            HashMap<String, Integer> followers = _bigrams.get(parts[0]);
            if (followers == null)
            {
              followers = new HashMap<>();
              _bigrams.put(parts[0], followers);
            }
            followers.put(parts[1], Integer.parseInt(parts[2]));
          }
          catch (NumberFormatException ignored) {}
        }
      }
      finally { br.close(); }
    }
    catch (IOException ignored) {}
  }
}
