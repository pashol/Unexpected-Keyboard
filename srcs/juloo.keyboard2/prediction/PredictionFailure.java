package juloo.keyboard2.prediction;

/** A recoverable experimental prediction failure. */
public final class PredictionFailure extends RuntimeException
{
  public PredictionFailure(String message)
  {
    super(message);
  }

  public PredictionFailure(String message, Throwable cause)
  {
    super(message, cause);
  }
}
