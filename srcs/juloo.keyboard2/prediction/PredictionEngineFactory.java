package juloo.keyboard2.prediction;

import juloo.keyboard2.Config;
import juloo.keyboard2.prediction.history.BoundedUserHistoryModel;
import juloo.keyboard2.prediction.history.FileUserHistoryStore;
import juloo.keyboard2.prediction.history.UserHistoryPersistence;

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
        (config, languageTag) -> {
          java.io.File pack = LatinPredictionAssetResolver.resolveActive(languageTag);
          UserHistoryPersistence history = new UserHistoryPersistence(
              new BoundedUserHistoryModel(System::currentTimeMillis),
              new FileUserHistoryStore(LatinPredictionAssetResolver.applicationContext()), languageTag);
          return createWithHistory(history, value -> new LatinPredictionDecoder(pack,
              languageTag, java.util.Collections.<PredictionRequest.KeyCenter>emptyList(), value));
        });
  }

  interface HistoryDecoderCreator
  {
    PredictionEngine create(UserHistoryPersistence history);
  }

  static PredictionEngine createWithHistory(UserHistoryPersistence history,
      HistoryDecoderCreator creator)
  {
    try { return creator.create(history); }
    catch (RuntimeException | Error failure) {
      history.close();
      throw failure;
    }
  }

  public PredictionEngineController create(Config config, String languageTag,
      boolean experimentalEnabled)
  {
    PredictionEngine legacy = legacyCreator.create(config);
    try {
      return new PredictionEngineController(
          legacy,
          createExperimental(config, languageTag, experimentalEnabled),
          experimentalEnabled);
    } catch (RuntimeException | Error failure) {
      legacy.close();
      throw failure;
    }
  }

  public void rebuild(PredictionEngineController controller, Config config,
      String languageTag, boolean experimentalEnabled)
  {
    controller.closeExperimentalForReplacement();
    PredictionEngine legacy = legacyCreator.create(config);
    PredictionEngine experimental;
    try {
      experimental = createExperimental(config, languageTag, experimentalEnabled);
    } catch (RuntimeException | Error failure) {
      legacy.close();
      throw failure;
    }
    controller.replaceEngines(legacy, experimental, experimentalEnabled);
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
    catch (PredictionFailure | LatinDecoder.InvalidDictionaryException unavailable)
    {
      return null;
    }
  }
}
