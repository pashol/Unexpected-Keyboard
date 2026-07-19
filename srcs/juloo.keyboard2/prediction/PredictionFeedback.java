package juloo.keyboard2.prediction;

import java.util.Objects;

public final class PredictionFeedback
{
  private final FeedbackType type;
  private final long generation;
  private final PredictionCandidate candidate;
  private final String committedText;
  private final long timestampMillis;

  public PredictionFeedback(
      FeedbackType type,
      long generation,
      PredictionCandidate candidate,
      String committedText,
      long timestampMillis)
  {
    this.type = Objects.requireNonNull(type, "type");
    if (candidate == null && type != FeedbackType.COMMITTED)
      throw new NullPointerException("candidate");
    this.generation = generation;
    this.candidate = candidate;
    this.committedText = Objects.requireNonNull(committedText, "committedText");
    this.timestampMillis = timestampMillis;
  }

  public FeedbackType getType()
  {
    return type;
  }

  public long getGeneration()
  {
    return generation;
  }

  public PredictionCandidate getCandidate()
  {
    return candidate;
  }

  public String getCommittedText()
  {
    return committedText;
  }

  public long getTimestampMillis()
  {
    return timestampMillis;
  }
}
