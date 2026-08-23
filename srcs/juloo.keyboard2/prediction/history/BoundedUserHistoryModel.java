package juloo.keyboard2.prediction.history;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Mutable writer which publishes immutable, bounded per-locale snapshots. */
public final class BoundedUserHistoryModel
{
  public static final int MAX_ENTRIES = 20000;
  public static final int MAX_BYTES = 2 * 1024 * 1024;
  private final HistoryClock clock;
  private final int maxEntries;
  private final int maxBytes;
  private final Map<String, Map<String, UserHistoryModel.Entry>> locales = new HashMap<>();

  public BoundedUserHistoryModel(HistoryClock clock) { this(clock, MAX_ENTRIES, MAX_BYTES); }
  public BoundedUserHistoryModel(HistoryClock clock, int maxEntries, int maxBytes)
  {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.maxEntries = maxEntries;
    this.maxBytes = maxBytes;
  }

  public synchronized void recordAccepted(String locale, List<String> preceding, String word)
  { adjust(locale, preceding, word, 1); }
  public synchronized void recordRejected(String locale, List<String> preceding, String word)
  { adjust(locale, preceding, word, -1); }
  public synchronized void recordReverted(String locale, List<String> preceding, String word)
  { adjust(locale, preceding, word, -1); }

  public synchronized void delete(String locale, String word)
  {
    Map<String, UserHistoryModel.Entry> entries = entries(locale);
    List<String> remove = new ArrayList<>();
    for (String key : entries.keySet())
      if (Arrays.asList(key.split(String.valueOf(UserHistoryModel.SEPARATOR), -1)).contains(word))
        remove.add(key);
    for (String key : remove) entries.remove(key);
  }

  public synchronized UserHistoryModel snapshot(String locale)
  {
    return new UserHistoryModel(entries(locale), clock);
  }

  synchronized void replace(String locale, UserHistoryModel snapshot)
  {
    locales.put(locale, new HashMap<String, UserHistoryModel.Entry>(snapshot.entries()));
    trim(locale);
  }

  synchronized void mergeLoaded(String locale, UserHistoryModel snapshot)
  { mergeLoaded(locale, snapshot, java.util.Collections.<String>emptySet()); }

  synchronized void mergeLoaded(String locale, UserHistoryModel snapshot, Set<String> deletedWords)
  {
    Map<String, UserHistoryModel.Entry> entries = entries(locale);
    for (Map.Entry<String, UserHistoryModel.Entry> entry : snapshot.entries().entrySet())
      if (!containsDeletedWord(entry.getKey(), deletedWords)) {
        UserHistoryModel.Entry current = entries.get(entry.getKey());
        if (current == null) entries.put(entry.getKey(), entry.getValue());
        else entries.put(entry.getKey(), new UserHistoryModel.Entry(
            current.count + entry.getValue().count,
            Math.max(current.updatedAt, entry.getValue().updatedAt)));
      }
    trim(locale);
  }

  private static boolean containsDeletedWord(String key, Set<String> deletedWords)
  {
    if (deletedWords.isEmpty()) return false;
    for (String word : key.split(String.valueOf(UserHistoryModel.SEPARATOR), -1))
      if (deletedWords.contains(word)) return true;
    return false;
  }

  private void adjust(String locale, List<String> preceding, String word, int delta)
  {
    Objects.requireNonNull(preceding, "preceding");
    Objects.requireNonNull(word, "word");
    Map<String, UserHistoryModel.Entry> entries = entries(locale);
    change(entries, UserHistoryModel.key(word), delta);
    int size = preceding.size();
    if (size > 0) change(entries, UserHistoryModel.key(preceding.get(size - 1), word), delta);
    if (size > 1) change(entries, UserHistoryModel.key(preceding.get(size - 2),
        preceding.get(size - 1), word), delta);
    trim(locale);
  }

  private void change(Map<String, UserHistoryModel.Entry> entries, String key, int delta)
  {
    UserHistoryModel.Entry old = entries.get(key);
    int count = (old == null ? 0 : old.count) + delta;
    if (count <= 0) entries.remove(key);
    else entries.put(key, new UserHistoryModel.Entry(count, clock.currentTimeMillis()));
  }

  private Map<String, UserHistoryModel.Entry> entries(String locale)
  {
    Objects.requireNonNull(locale, "locale");
    Map<String, UserHistoryModel.Entry> result = locales.get(locale);
    if (result == null) { result = new HashMap<>(); locales.put(locale, result); }
    return result;
  }

  private void trim(String locale)
  {
    Map<String, UserHistoryModel.Entry> entries = entries(locale);
    while (entries.size() > maxEntries || new UserHistoryModel(entries, clock).serializedBytes() > maxBytes
        || new UserHistoryModel(entries, clock).hasUnencodableEntry()) {
      String evict = null;
      for (Map.Entry<String, UserHistoryModel.Entry> entry : entries.entrySet())
        if (evict == null || entry.getValue().updatedAt < entries.get(evict).updatedAt
            || (entry.getValue().updatedAt == entries.get(evict).updatedAt
                && entry.getKey().compareTo(evict) < 0)) evict = entry.getKey();
      entries.remove(evict);
    }
  }

}
