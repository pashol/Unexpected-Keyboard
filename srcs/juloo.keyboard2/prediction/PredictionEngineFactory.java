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
    return new PredictionEngineController(
        legacyCreator.create(config),
        experimentalCreator.create(config, languageTag),
        experimentalEnabled);
  }

  public void rebuild(PredictionEngineController controller, Config config,
      String languageTag, boolean experimentalEnabled)
  {
    controller.replaceEngines(
        legacyCreator.create(config),
        experimentalCreator.create(config, languageTag),
        experimentalEnabled);
  }
}
