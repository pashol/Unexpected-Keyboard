package juloo.keyboard2.prediction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PredictionRequest
{
  private static final int MAX_PRECEDING_WORDS = 3;

  private final List<String> _preceding_words;
  private final int _max_candidates;
  private final int _generation;

  public PredictionRequest(List<String> words, int maxCandidates, int generation)
  {
    if (maxCandidates <= 0)
      throw new IllegalArgumentException("Max candidates must be positive");
    int start = Math.max(words.size() - MAX_PRECEDING_WORDS, 0);
    _preceding_words = Collections.unmodifiableList(
        new ArrayList<String>(words.subList(start, words.size())));
    _max_candidates = maxCandidates;
    _generation = generation;
  }

  public List<String> preceding_words()
  {
    return _preceding_words;
  }

  public int max_candidates()
  {
    return _max_candidates;
  }

  public int generation()
  {
    return _generation;
  }
}
