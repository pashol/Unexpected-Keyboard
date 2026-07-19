package juloo.keyboard2.prediction;

import juloo.keyboard2.Config;

/** Narrow creation seams for locale-dependent prediction engines. */
public final class PredictionEngineFactory
{
  public interface LegacyCreator
  {
    PredictionEngine create(Config config);
  }

  public interface ExperimentalCreator
  {
    PredictionEngine create(Config config, String languageTag);
  }

  private final LegacyCreator legacyCreator;
  private final ExperimentalCreator experimentalCreator;

  public PredictionEngineFactory(LegacyCreator legacyCreator,
      ExperimentalCreator experimentalCreator)
  {
    this.legacyCreator = legacyCreator;
    this.experimentalCreator = experimentalCreator;
  }

  public static PredictionEngineFactory production()
  {
    return new PredictionEngineFactory(
        LegacyPredictionEngine::new,
        (config, languageTag) -> null);
  }

  public PredictionEngineController create(Config config, String languageTag,
      boolean experimentalEnabled)
  {
    PredictionEngine legacy = legacyCreator.create(config);
    return new PredictionEngineController(
        legacy,
        createExperimental(config, languageTag, experimentalEnabled),
        experimentalEnabled);
  }

  public void rebuild(PredictionEngineController controller, Config config,
      String languageTag, boolean experimentalEnabled)
  {
    controller.replaceEngines(
        legacyCreator.create(config),
        createExperimental(config, languageTag, experimentalEnabled),
        experimentalEnabled);
  }

  private PredictionEngine createExperimental(Config config, String languageTag,
      boolean experimentalEnabled)
  {
    if (!experimentalEnabled)
      return null;
    try
    {
      return experimentalCreator.create(config, languageTag);
    }
    catch (PredictionFailure unavailable)
    {
      return null;
    }
  }
}
