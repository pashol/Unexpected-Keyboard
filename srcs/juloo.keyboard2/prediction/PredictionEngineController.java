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
  private long previousGeneration = Long.MIN_VALUE;
  private PredictionEngine previousProducer;
  private long retainedGeneration = Long.MIN_VALUE;
  private PredictionEngine retainedProducer;
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

    previousGeneration = currentGeneration;
    previousProducer = currentProducer;
    currentGeneration = request.getGeneration();
    currentProducer = null;
    currentExperimental = false;
    if (!request.getEditorPredictionPolicy().allowsPrediction())
    {
      previousGeneration = Long.MIN_VALUE;
      previousProducer = null;
      return Collections.emptyList();
    }

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
    if (!closed && feedback.getGeneration() == currentGeneration
        && currentProducer != null)
      currentProducer.recordFeedback(feedback);
    else if (!closed && feedback.getGeneration() == previousGeneration
        && previousProducer != null)
      previousProducer.recordFeedback(feedback);
    else if (!closed && feedback.getGeneration() == retainedGeneration
        && retainedProducer != null)
      retainedProducer.recordFeedback(feedback);
  }

  /** Retains one accepted candidate's producer until its immediate undo resolves. */
  public synchronized void retainFeedbackGeneration(long generation)
  {
    if (generation == currentGeneration)
    {
      retainedGeneration = generation;
      retainedProducer = currentProducer;
    }
    else if (generation == previousGeneration)
    {
      retainedGeneration = generation;
      retainedProducer = previousProducer;
    }
  }

  public synchronized void releaseFeedbackGeneration(long generation)
  {
    if (generation == retainedGeneration)
    {
      retainedGeneration = Long.MIN_VALUE;
      retainedProducer = null;
    }
  }

  /** Advances an adapted snapshot without issuing another prediction request. */
  public synchronized boolean adoptGeneration(long generation)
  {
    if (closed || currentProducer == null || generation <= currentGeneration)
      return false;
    previousGeneration = currentGeneration;
    previousProducer = currentProducer;
    currentGeneration = generation;
    return true;
  }

  @Override
  public synchronized void resetSession()
  {
    if (closed)
      return;
    experimentalFailed = false;
    currentGeneration = Long.MIN_VALUE;
    currentProducer = null;
    previousGeneration = Long.MIN_VALUE;
    previousProducer = null;
    retainedGeneration = Long.MIN_VALUE;
    retainedProducer = null;
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
    previousGeneration = Long.MIN_VALUE;
    previousProducer = null;
    retainedGeneration = Long.MIN_VALUE;
    retainedProducer = null;
    currentExperimental = false;
    closeIfReplaced(oldLegacy, newLegacy, newExperimental);
    closeIfReplaced(oldExperimental, newLegacy, newExperimental);
  }

  /** Releases per-engine resources before a replacement begins construction. */
  public synchronized void closeExperimentalForReplacement()
  {
    if (closed || experimental == null) return;
    PredictionEngine old = experimental;
    experimental = null;
    currentProducer = currentProducer == old ? null : currentProducer;
    previousProducer = previousProducer == old ? null : previousProducer;
    retainedProducer = retainedProducer == old ? null : retainedProducer;
    currentExperimental = false;
    closeOnce(old);
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
    previousProducer = null;
    retainedProducer = null;
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
