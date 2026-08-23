package juloo.keyboard2;

/** Routes service lifecycle events to the owned prediction controller. */
final class PredictionEngineLifecycleCoordinator implements AutoCloseable
{
  interface Target
  {
    void rebuildPredictionEngine();
    void resetPredictionSession();
    void closePredictionEngines();
  }

  private final Target target;
  private boolean closed;

  PredictionEngineLifecycleCoordinator(Target target)
  {
    this.target = target;
  }

  void onPreferenceChanged(String key)
  {
    if (!closed && "experimental_prediction_engine".equals(key))
      target.rebuildPredictionEngine();
  }

  void onSubtypeChanged()
  {
    target.rebuildPredictionEngine();
  }

  void onInputFinished()
  {
    target.resetPredictionSession();
  }

  @Override
  public void close()
  {
    if (closed)
      return;
    closed = true;
    target.closePredictionEngines();
  }
}
