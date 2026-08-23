package juloo.keyboard2.prediction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Applies the stable ordering contract after policy admission. */
public final class CandidateRanker
{
  private CandidateRanker() {}

  public static List<PredictionCandidate> rank(List<PredictionCandidate> candidates,
      final String typedWord)
  {
    ArrayList<PredictionCandidate> ranked = new ArrayList<PredictionCandidate>(candidates);
    Collections.sort(ranked, new Comparator<PredictionCandidate>() {
      public int compare(PredictionCandidate left, PredictionCandidate right)
      {
        int result = Boolean.compare(isTyped(right, typedWord), isTyped(left, typedWord));
        if (result != 0) return result;
        result = Double.compare(right.getPersonalizationScore(), left.getPersonalizationScore());
        if (result != 0) return result;
        result = Double.compare(right.getContextScore(), left.getContextScore());
        if (result != 0) return result;
        result = Double.compare(right.getLexicalScore(), left.getLexicalScore());
        if (result != 0) return result;
        result = Double.compare(left.getTouchEditCost(), right.getTouchEditCost());
        if (result != 0) return result;
        return left.getText().compareTo(right.getText());
      }
    });
    return Collections.unmodifiableList(ranked);
  }

  private static boolean isTyped(PredictionCandidate candidate, String typedWord)
  {
    return candidate.getType() == CandidateType.TYPED && candidate.getText().equals(typedWord);
  }
}
