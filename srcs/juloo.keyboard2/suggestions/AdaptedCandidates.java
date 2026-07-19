package juloo.keyboard2.suggestions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import juloo.keyboard2.prediction.PredictionCandidate;

/** Structured candidates projected into the legacy Suggestions display model. */
public final class AdaptedCandidates
{
  private final String[] suggestions;
  private final boolean[] personalSuggestions;
  private final int count;
  private final List<PredictionCandidate> candidates;
  private final long generation;
  private final boolean experimental;

  AdaptedCandidates(String[] suggestions, boolean[] personalSuggestions,
      int count, List<PredictionCandidate> candidates, long generation)
  {
    this(suggestions, personalSuggestions, count, candidates, generation, false);
  }

  AdaptedCandidates(String[] suggestions, boolean[] personalSuggestions,
      int count, List<PredictionCandidate> candidates, long generation,
      boolean experimental)
  {
    this.suggestions = suggestions.clone();
    this.personalSuggestions = personalSuggestions.clone();
    this.count = count;
    this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
    this.generation = generation;
    this.experimental = experimental;
  }

  public String[] getSuggestions() { return suggestions.clone(); }
  public boolean[] getPersonalSuggestions() { return personalSuggestions.clone(); }
  public int getCount() { return count; }
  public List<PredictionCandidate> getCandidates() { return candidates; }
  public long getGeneration() { return generation; }
  public boolean isExperimental() { return experimental; }
}
