package juloo.keyboard2.prediction;

import android.text.InputType;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class PredictionEngineFactoryTest
{
  @Test
  public void disabled_factory_does_not_create_experimental_resources()
  {
    CountingCreator creators = new CountingCreator();
    PredictionEngineFactory factory = new PredictionEngineFactory(
        config -> creators.legacy(),
        (config, languageTag) -> creators.experimental());

    PredictionEngineController controller = factory.create(null, "en", false);

    assertEquals(1, creators.legacyCreates);
    assertEquals(0, creators.experimentalCreates);
    assertEquals("legacy", controller.predict(request()).get(0).getText());
  }

  @Test
  public void recoverable_experimental_construction_failure_returns_legacy_controller()
  {
    CountingCreator creators = new CountingCreator();
    PredictionEngineFactory factory = new PredictionEngineFactory(
        config -> creators.legacy(),
        (config, languageTag) -> {
          creators.experimentalCreates++;
          throw new PredictionFailure("pack unavailable");
        });

    PredictionEngineController controller = factory.create(null, "en", true);

    assertEquals("legacy", controller.predict(request()).get(0).getText());
    assertEquals(1, creators.experimentalCreates);
  }

  @Test(expected = IllegalStateException.class)
  public void programming_runtime_from_experimental_creator_is_not_swallowed()
  {
    PredictionEngineFactory factory = new PredictionEngineFactory(
        config -> new CountingEngine("legacy"),
        (config, languageTag) -> { throw new IllegalStateException("bug"); });

    factory.create(null, "en", true);
  }

  @Test(expected = AssertionError.class)
  public void error_from_experimental_creator_is_not_swallowed()
  {
    PredictionEngineFactory factory = new PredictionEngineFactory(
        config -> new CountingEngine("legacy"),
        (config, languageTag) -> { throw new AssertionError("fatal"); });

    factory.create(null, "en", true);
  }

  @Test
  public void disabled_rebuild_skips_experimental_and_closes_replaced_engine_once()
  {
    CountingEngine oldLegacy = new CountingEngine("old legacy");
    CountingEngine oldExperimental = new CountingEngine("old experimental");
    PredictionEngineController controller =
        new PredictionEngineController(oldLegacy, oldExperimental, true);
    CountingCreator creators = new CountingCreator();
    PredictionEngineFactory factory = new PredictionEngineFactory(
        config -> creators.legacy(),
        (config, languageTag) -> creators.experimental());

    factory.rebuild(controller, null, "fr", false);

    assertEquals(0, creators.experimentalCreates);
    assertEquals(1, oldLegacy.closes);
    assertEquals(1, oldExperimental.closes);
    assertEquals("legacy", controller.predict(request()).get(0).getText());
  }

  private static PredictionRequest request()
  {
    return new PredictionRequest("word", 4, Collections.emptyList(), false,
        "en", 3, 1L, EditorPredictionPolicy.from(
          InputType.TYPE_CLASS_TEXT, 0, null));
  }

  private static final class CountingCreator
  {
    int legacyCreates;
    int experimentalCreates;

    PredictionEngine legacy()
    {
      legacyCreates++;
      return new CountingEngine("legacy");
    }

    PredictionEngine experimental()
    {
      experimentalCreates++;
      return new CountingEngine("experimental");
    }
  }

  private static final class CountingEngine implements PredictionEngine
  {
    final String text;
    int closes;

    CountingEngine(String text) { this.text = text; }

    public List<PredictionCandidate> predict(PredictionRequest request)
    {
      return Collections.singletonList(new PredictionCandidate(
          text, "en", CandidateType.COMPLETION, text, 0, 0, 0, 0, 0));
    }

    public void recordFeedback(PredictionFeedback feedback) {}
    public void resetSession() {}
    public void close() { closes++; }
  }
}
