package juloo.keyboard2.suggestions;

import java.util.ArrayList;
import java.util.List;
import juloo.keyboard2.prediction.LegacyPredictionEngine;
import juloo.keyboard2.prediction.PredictionCandidate;

public final class PredictionCandidateAdapter
{
  private PredictionCandidateAdapter() {}

  public static AdaptedCandidates adapt(
      List<PredictionCandidate> candidates, long generation)
  {
    String[] suggestions = new String[Suggestions.MAX_COUNT];
    boolean[] personal = new boolean[Suggestions.MAX_COUNT];
    ArrayList<PredictionCandidate> retained = new ArrayList<>(Suggestions.MAX_COUNT);
    for (PredictionCandidate candidate : candidates)
    {
      if (retained.size() >= Suggestions.MAX_COUNT || contains(retained, candidate.getText()))
        continue;
      int index = retained.size();
      retained.add(candidate);
      suggestions[index] = candidate.getText();
      personal[index] = LegacyPredictionEngine.SOURCE_PERSONAL.equals(candidate.getSource());
    }
    return new AdaptedCandidates(
        suggestions, personal, retained.size(), retained, generation);
  }

  static AdaptedCandidates removePersonalCandidate(
      AdaptedCandidates candidates, String text)
  {
    ArrayList<PredictionCandidate> retained = new ArrayList<>();
    for (PredictionCandidate candidate : candidates.getCandidates())
      if (!LegacyPredictionEngine.SOURCE_PERSONAL.equals(candidate.getSource())
          || !candidate.getText().equals(text))
        retained.add(candidate);
    return adapt(retained, candidates.getGeneration());
  }

  private static boolean contains(List<PredictionCandidate> candidates, String text)
  {
    for (PredictionCandidate candidate : candidates)
      if (candidate.getText().equalsIgnoreCase(text))
        return true;
    return false;
  }
}
