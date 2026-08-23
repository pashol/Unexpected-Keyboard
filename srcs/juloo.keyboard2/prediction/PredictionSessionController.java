package juloo.keyboard2.prediction;

import java.util.Collections;
import java.util.List;

/** Owns the controller for the currently active editor session. */
public final class PredictionSessionController
{
  private PredictionEngineController _controller;

  public void replace(PredictionEngineController controller)
  {
    if (_controller != null)
      _controller.close();
    _controller = controller;
  }

  public PredictionEngineController controller()
  {
    return _controller;
  }

  public List<PredictionCandidate> predict(PredictionRequest request)
  {
    return _controller == null ? Collections.<PredictionCandidate>emptyList()
      : _controller.predict(request);
  }

  public void close()
  {
    replace(null);
  }
}
