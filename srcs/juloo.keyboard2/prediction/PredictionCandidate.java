package juloo.keyboard2.prediction;

public final class PredictionCandidate
{
  private final String _text;
  private final float _score;

  public PredictionCandidate(String text, float score)
  {
    if (text == null || text.length() == 0)
      throw new IllegalArgumentException("Candidate text must not be empty");
    _text = text;
    _score = score;
  }

  public String text()
  {
    return _text;
  }

  public float score()
  {
    return _score;
  }
}
