package juloo.keyboard2.suggestions;

import java.util.Arrays;

/** Sliding-window tracker that records which dictionary produced the best
    match for each recently-confirmed word. Used by [Suggestions] to break
    ties when multiple active dictionaries match equally well. */
public final class LanguageMomentum
{
  public static final int WINDOW_SIZE = 8;
  /** Initial score given to the default dictionary when the buffer is empty
      (new field opened). Keeps first suggestions in the right language without
      needing a warm-up period. */
  public static final float INITIAL_MOMENTUM = 0.5f;

  private final int[] _buffer; // circular buffer; -1 = no winner for that slot
  private int _head = 0;
  private int _recorded = 0; // count of non-(-1) entries
  private int _default_index;

  public LanguageMomentum(int default_index)
  {
    _buffer = new int[WINDOW_SIZE];
    reset(default_index);
  }

  /** Reset to initial state, giving [default_index] the INITIAL_MOMENTUM advantage. */
  public void reset(int default_index)
  {
    _default_index = default_index;
    Arrays.fill(_buffer, -1);
    _head = 0;
    _recorded = 0;
  }

  /** Record the winning dict index for a confirmed word. Pass -1 if no dict matched. */
  public void record(int winner_index)
  {
    int old = _buffer[_head];
    if (old >= 0) _recorded--;
    _buffer[_head] = winner_index;
    if (winner_index >= 0) _recorded++;
    _head = (_head + 1) % WINDOW_SIZE;
  }

  /** Returns the momentum score (0.0–1.0) for [dict_index].
      When the buffer has no history, the default dict gets INITIAL_MOMENTUM and
      all others get 0, biasing initial suggestions toward the default language. */
  public float score(int dict_index)
  {
    if (_recorded == 0)
      return (dict_index == _default_index) ? INITIAL_MOMENTUM : 0f;
    int hits = 0;
    for (int v : _buffer)
      if (v == dict_index) hits++;
    return (float) hits / WINDOW_SIZE;
  }
}
