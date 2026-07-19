package juloo.keyboard2.prediction;

import android.text.InputType;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class PredictionEngineControllerTest
{
  @Test
  public void disabled_selects_legacy()
  {
    CountingEngine legacy = engine("legacy");
    CountingEngine experimental = engine("experimental");
    PredictionEngineController controller =
      new PredictionEngineController(legacy, experimental, false);

    assertEquals("legacy", controller.predict(request(1, true)).get(0).getText());
    assertEquals(1, legacy.predicts);
    assertEquals(0, experimental.predicts);
    assertFalse(controller.wasLastResultExperimental());
  }

  @Test
  public void enabled_available_selects_experimental()
  {
    CountingEngine legacy = engine("legacy");
    CountingEngine experimental = engine("experimental");
    PredictionEngineController controller =
      new PredictionEngineController(legacy, experimental, true);

    assertEquals("experimental", controller.predict(request(1, true)).get(0).getText());
    assertEquals(0, legacy.predicts);
    assertEquals(1, experimental.predicts);
    assertTrue(controller.wasLastResultExperimental());
  }

  @Test
  public void enabled_missing_experimental_selects_legacy()
  {
    CountingEngine legacy = engine("legacy");
    PredictionEngineController controller =
      new PredictionEngineController(legacy, null, true);

    assertEquals("legacy", controller.predict(request(1, true)).get(0).getText());
    assertEquals(1, legacy.predicts);
    assertFalse(controller.wasLastResultExperimental());
  }

  @Test
  public void denied_request_calls_neither_engine()
  {
    CountingEngine legacy = engine("legacy");
    CountingEngine experimental = engine("experimental");
    PredictionEngineController controller =
      new PredictionEngineController(legacy, experimental, true);

    assertTrue(controller.predict(request(1, false)).isEmpty());
    assertEquals(0, legacy.predicts);
    assertEquals(0, experimental.predicts);
  }

  @Test
  public void runtime_failure_falls_back_for_same_request_and_latches_legacy()
  {
    CountingEngine legacy = engine("legacy");
    CountingEngine experimental = engine("experimental");
    experimental.failure = new RuntimeException("decoder failed");
    PredictionEngineController controller =
      new PredictionEngineController(legacy, experimental, true);

    assertEquals("legacy", controller.predict(request(1, true)).get(0).getText());
    experimental.failure = null;
    assertEquals("legacy", controller.predict(request(2, true)).get(0).getText());
    assertEquals(1, experimental.predicts);
    assertEquals(2, legacy.predicts);
  }

  @Test
  public void explicit_recoverable_failure_falls_back_and_latches_legacy()
  {
    CountingEngine legacy = engine("legacy");
    CountingEngine experimental = engine("experimental");
    experimental.failure = new PredictionFailure("pack unavailable");
    PredictionEngineController controller =
      new PredictionEngineController(legacy, experimental, true);

    assertEquals("legacy", controller.predict(request(1, true)).get(0).getText());
    experimental.failure = null;
    controller.predict(request(2, true));
    assertEquals(1, experimental.predicts);
    assertEquals(2, legacy.predicts);
  }

  @Test
  public void reset_clears_failure_latch_resets_engines_and_allows_retry()
  {
    CountingEngine legacy = engine("legacy");
    CountingEngine experimental = engine("experimental");
    experimental.failure = new RuntimeException("decoder failed");
    PredictionEngineController controller =
      new PredictionEngineController(legacy, experimental, true);
    controller.predict(request(1, true));

    experimental.failure = null;
    controller.resetSession();

    assertEquals("experimental", controller.predict(request(1, true)).get(0).getText());
    assertEquals(1, legacy.resets);
    assertEquals(1, experimental.resets);
  }

  @Test
  public void replacing_engines_closes_each_replaced_instance_once()
  {
    CountingEngine oldLegacy = engine("old legacy");
    CountingEngine oldExperimental = engine("old experimental");
    CountingEngine newLegacy = engine("new legacy");
    CountingEngine newExperimental = engine("new experimental");
    PredictionEngineController controller =
      new PredictionEngineController(oldLegacy, oldExperimental, true);

    controller.replaceEngines(newLegacy, newExperimental, true);

    assertEquals(1, oldLegacy.closes);
    assertEquals(1, oldExperimental.closes);
    assertEquals(0, newLegacy.closes);
    assertEquals(0, newExperimental.closes);
  }

  @Test
  public void close_is_idempotent_and_shared_engine_is_closed_once()
  {
    CountingEngine shared = engine("shared");
    PredictionEngineController controller =
      new PredictionEngineController(shared, shared, true);

    controller.close();
    controller.close();

    assertEquals(1, shared.closes);
  }

  @Test
  public void feedback_routes_only_to_current_generations_producer()
  {
    CountingEngine legacy = engine("legacy");
    CountingEngine experimental = engine("experimental");
    PredictionEngineController controller =
      new PredictionEngineController(legacy, experimental, true);
    PredictionCandidate candidate = controller.predict(request(7, true)).get(0);

    controller.recordFeedback(feedback(6, candidate));
    controller.recordFeedback(feedback(7, candidate));

    assertEquals(0, legacy.feedback);
    assertEquals(1, experimental.feedback);
    assertTrue(controller.isGenerationExperimental(7));
    assertFalse(controller.isGenerationExperimental(6));
  }

  @Test
  public void stale_and_duplicate_requests_are_ignored_without_changing_source()
  {
    CountingEngine legacy = engine("legacy");
    CountingEngine experimental = engine("experimental");
    PredictionEngineController controller =
      new PredictionEngineController(legacy, experimental, true);
    controller.predict(request(8, true));

    assertTrue(controller.predict(request(7, true)).isEmpty());
    assertTrue(controller.predict(request(8, true)).isEmpty());
    assertEquals(1, experimental.predicts);
    assertTrue(controller.wasLastResultExperimental());
    assertTrue(controller.isGenerationExperimental(8));
  }

  private static PredictionRequest request(long generation, boolean allowed)
  {
    int type = allowed
      ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL
      : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
    return new PredictionRequest("word", 4, Collections.emptyList(), false,
        "en", 3, generation, EditorPredictionPolicy.from(type, 0, null));
  }

  private static PredictionFeedback feedback(long generation,
      PredictionCandidate candidate)
  {
    return new PredictionFeedback(
        FeedbackType.ACCEPTED, generation, candidate, candidate.getText(), 1L);
  }

  private static CountingEngine engine(String result)
  {
    return new CountingEngine(Collections.singletonList(new PredictionCandidate(
        result, "en", CandidateType.COMPLETION, result, 0, 0, 0, 0, 0)));
  }

  private static final class CountingEngine implements PredictionEngine
  {
    final List<PredictionCandidate> result;
    RuntimeException failure;
    int predicts;
    int feedback;
    int resets;
    int closes;

    CountingEngine(List<PredictionCandidate> result) { this.result = result; }

    public List<PredictionCandidate> predict(PredictionRequest request)
    {
      predicts++;
      if (failure != null)
        throw failure;
      return result;
    }

    public void recordFeedback(PredictionFeedback value) { feedback++; }
    public void resetSession() { resets++; }
    public void close() { closes++; }
  }
}
