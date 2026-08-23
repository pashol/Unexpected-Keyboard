package juloo.keyboard2.prediction.history;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable, locale-specific history view used by prediction. */
public final class UserHistoryModel
{
  static final char SEPARATOR = '\u0001';
  static final class Entry
  {
    final int count;
    final long updatedAt;
    Entry(int count, long updatedAt) { this.count = count; this.updatedAt = updatedAt; }
  }

  private final Map<String, Entry> entries;
  private final HistoryClock clock;

  UserHistoryModel(Map<String, Entry> entries, HistoryClock clock)
  {
    this.entries = Collections.unmodifiableMap(new LinkedHashMap<String, Entry>(entries));
    this.clock = clock;
  }

  public static UserHistoryModel empty(HistoryClock clock)
  {
    return new UserHistoryModel(Collections.<String, Entry>emptyMap(), clock);
  }

  public int unigramCount(String word) { return count(key(word)); }
  public int bigramCount(String first, String second) { return count(key(first, second)); }
  public int trigramCount(String first, String second, String third)
  { return count(key(first, second, third)); }

  public double unigramScore(String word) { return score(key(word)); }
  public double bigramScore(String first, String second) { return score(key(first, second)); }
  public double trigramScore(String first, String second, String third)
  { return score(key(first, second, third)); }

  int size() { return entries.size(); }
  Map<String, Entry> entries() { return entries; }

  int serializedBytes()
  {
    int result = 12;
    for (String key : entries.keySet()) result += 2 + modifiedUtfBytes(key) + 12;
    return result;
  }

  boolean hasUnencodableEntry()
  {
    for (String key : entries.keySet()) if (modifiedUtfBytes(key) > 65535) return true;
    return false;
  }

  static int modifiedUtfBytes(String text)
  {
    int result = 0;
    for (int i = 0; i < text.length(); i++) {
      int value = text.charAt(i);
      result += value >= 1 && value <= 0x7f ? 1 : value <= 0x7ff ? 2 : 3;
    }
    return result;
  }

  private int count(String key)
  {
    Entry entry = entries.get(key);
    return entry == null ? 0 : entry.count;
  }

  private double score(String key)
  {
    Entry entry = entries.get(key);
    if (entry == null) return 0;
    long age = Math.max(0, clock.currentTimeMillis() - entry.updatedAt);
    return entry.count * Math.pow(0.5, age / (30d * 24 * 60 * 60 * 1000));
  }

  static String key(String... words)
  {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < words.length; i++) {
      if (i > 0) result.append(SEPARATOR);
      result.append(words[i]);
    }
    return result.toString();
  }
}
