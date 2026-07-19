package juloo.keyboard2.prediction;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Owns prediction engines and routes each synchronous request to one engine. */
public final class PredictionEngineController implements PredictionEngine
{
  private PredictionEngine legacy;
  private PredictionEngine experimental;
  private boolean experimentalEnabled;
  private boolean experimentalFailed;
  private boolean closed;
  private long currentGeneration = Long.MIN_VALUE;
  private PredictionEngine currentProducer;
  private boolean currentExperimental;
  private final Set<PredictionEngine> closedEngines =
      Collections.newSetFromMap(new IdentityHashMap<PredictionEngine, Boolean>());

  public PredictionEngineController(PredictionEngine legacy,
      PredictionEngine experimental, boolean experimentalEnabled)
  {
    this.legacy = Objects.requireNonNull(legacy, "legacy");
    this.experimental = experimental;
    this.experimentalEnabled = experimentalEnabled;
  }

  @Override
  public synchronized List<PredictionCandidate> predict(PredictionRequest request)
  {
    Objects.requireNonNull(request, "request");
    if (closed || request.getGeneration() <= currentGeneration)
      return Collections.emptyList();

    currentGeneration = request.getGeneration();
    currentProducer = null;
    currentExperimental = false;
    if (!request.getEditorPredictionPolicy().allowsPrediction())
      return Collections.emptyList();

    if (experimentalEnabled && experimental != null && !experimentalFailed)
    {
      try
      {
        List<PredictionCandidate> result = experimental.predict(request);
        currentProducer = experimental;
        currentExperimental = true;
        return result;
      }
      catch (RuntimeException recoverable)
      {
        experimentalFailed = true;
      }
    }

    List<PredictionCandidate> result = legacy.predict(request);
    currentProducer = legacy;
    return result;
  }

  @Override
  public synchronized void recordFeedback(PredictionFeedback feedback)
  {
    Objects.requireNonNull(feedback, "feedback");
    if (!closed && currentProducer != null
        && feedback.getGeneration() == currentGeneration)
      currentProducer.recordFeedback(feedback);
  }

  @Override
  public synchronized void resetSession()
  {
    if (closed)
      return;
    experimentalFailed = false;
    currentGeneration = Long.MIN_VALUE;
    currentProducer = null;
    currentExperimental = false;
    legacy.resetSession();
    if (experimental != null && experimental != legacy)
      experimental.resetSession();
  }

  public synchronized void replaceEngines(PredictionEngine newLegacy,
      PredictionEngine newExperimental, boolean newExperimentalEnabled)
  {
    Objects.requireNonNull(newLegacy, "legacy");
    if (closed)
      throw new IllegalStateException("controller is closed");
    PredictionEngine oldLegacy = legacy;
    PredictionEngine oldExperimental = experimental;
    legacy = newLegacy;
    experimental = newExperimental;
    experimentalEnabled = newExperimentalEnabled;
    experimentalFailed = false;
    currentGeneration = Long.MIN_VALUE;
    currentProducer = null;
    currentExperimental = false;
    closeIfReplaced(oldLegacy, newLegacy, newExperimental);
    closeIfReplaced(oldExperimental, newLegacy, newExperimental);
  }

  public synchronized boolean wasLastResultExperimental()
  {
    return currentExperimental;
  }

  public synchronized boolean isGenerationExperimental(long generation)
  {
    return generation == currentGeneration && currentExperimental;
  }

  @Override
  public synchronized void close()
  {
    if (closed)
      return;
    closed = true;
    closeOnce(legacy);
    closeOnce(experimental);
    currentProducer = null;
    currentExperimental = false;
  }

  private void closeIfReplaced(PredictionEngine engine,
      PredictionEngine newLegacy, PredictionEngine newExperimental)
  {
    if (engine != newLegacy && engine != newExperimental)
      closeOnce(engine);
  }

  private void closeOnce(PredictionEngine engine)
  {
    if (engine != null && closedEngines.add(engine))
      engine.close();
  }
}
