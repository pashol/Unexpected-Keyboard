package juloo.keyboard2.prediction;

import java.util.List;

public final class PredictionEngineController implements PredictionEngine
{
  private final boolean _enabled;
  private final PredictionEngine _experimental;
  private final PredictionEngine _legacy;
  private boolean _experimental_failed = false;
  private boolean _closed = false;

  public PredictionEngineController(boolean enabled, PredictionEngine experimental,
      PredictionEngine legacy)
  {
    _enabled = enabled;
    _experimental = experimental;
    _legacy = legacy;
  }

  public List<PredictionCandidate> predict(PredictionRequest request)
  {
    if (_enabled && !_experimental_failed)
    {
      try
      {
        return _experimental.predict(request);
      }
      catch (RuntimeException e)
      {
        _experimental_failed = true;
      }
    }
    return _legacy.predict(request);
  }

  public void reset_session()
  {
    _experimental_failed = false;
    _experimental.reset_session();
    _legacy.reset_session();
  }

  public void close()
  {
    if (_closed)
      return;
    _closed = true;
    try
    {
      _experimental.close();
    }
    finally
    {
      if (_legacy != _experimental)
        _legacy.close();
    }
  }
}
