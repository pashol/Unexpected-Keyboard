package juloo.keyboard2.prediction;

import java.util.List;

public interface PredictionEngine extends AutoCloseable
{
  List<PredictionCandidate> predict(PredictionRequest request);
  void recordFeedback(PredictionFeedback feedback);
  void resetSession();
  void close();
}
