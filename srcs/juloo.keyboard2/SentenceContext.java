package juloo.keyboard2;

import java.util.ArrayDeque;

/** Tracks the last N committed words for context-aware suggestions. */
public final class SentenceContext
{
  private static final int MAX_CONTEXT = 5;
  private final ArrayDeque<String> _words = new ArrayDeque<>();

  public void word_committed(String word)
  {
    if (word == null || word.isEmpty()) return;
    if (_words.size() >= MAX_CONTEXT) _words.pollFirst();
    _words.addLast(word.toLowerCase());
  }

  public void reset() { _words.clear(); }

  /** Returns the most recently committed word, or null if none. */
  public String last()
  {
    return _words.isEmpty() ? null : _words.peekLast();
  }
}
