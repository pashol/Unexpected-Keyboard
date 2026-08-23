package juloo.keyboard2.prediction;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class PredictionEngineControllerTest
{
  private static final PredictionRequest REQUEST = new PredictionRequest(
      Collections.singletonList("hello"), 3, 1);

  @Test
  public void controller_latches_to_legacy_after_decoder_failure()
  {
    RecordingEngine failing = new RecordingEngine(true, "experimental");
    RecordingEngine legacy = new RecordingEngine(false, "legacy");
    PredictionEngineController controller = new PredictionEngineController(
        true, failing, legacy);

    assertEquals("legacy", controller.predict(REQUEST).get(0).text());
    assertEquals("legacy", controller.predict(REQUEST).get(0).text());
    assertEquals(1, failing.predict_calls);
    assertEquals(2, legacy.predict_calls);
  }

  @Test
  public void controller_uses_legacy_when_disabled()
  {
    RecordingEngine experimental = new RecordingEngine(false, "experimental");
    RecordingEngine legacy = new RecordingEngine(false, "legacy");
    PredictionEngineController controller = new PredictionEngineController(
        false, experimental, legacy);

    assertEquals("legacy", controller.predict(REQUEST).get(0).text());
    assertEquals(0, experimental.predict_calls);
    assertEquals(1, legacy.predict_calls);
  }

  @Test
  public void controller_reset_clears_failure_latch_and_resets_both_engines()
  {
    RecordingEngine failing = new RecordingEngine(true, "experimental");
    RecordingEngine legacy = new RecordingEngine(false, "legacy");
    PredictionEngineController controller = new PredictionEngineController(
        true, failing, legacy);

    controller.predict(REQUEST);
    controller.reset_session();
    controller.predict(REQUEST);

    assertEquals(2, failing.predict_calls);
    assertEquals(2, legacy.predict_calls);
    assertEquals(1, failing.reset_calls);
    assertEquals(1, legacy.reset_calls);
  }

  @Test
  public void controller_closes_each_engine_once()
  {
    RecordingEngine experimental = new RecordingEngine(false, "experimental");
    RecordingEngine legacy = new RecordingEngine(false, "legacy");
    PredictionEngineController controller = new PredictionEngineController(
        true, experimental, legacy);

    controller.close();
    controller.close();

    assertEquals(1, experimental.close_calls);
    assertEquals(1, legacy.close_calls);
  }

  private static final class RecordingEngine implements PredictionEngine
  {
    private final boolean _fails;
    private final String _result;
    int predict_calls;
    int reset_calls;
    int close_calls;

    RecordingEngine(boolean fails, String result)
    {
      _fails = fails;
      _result = result;
    }

    public List<PredictionCandidate> predict(PredictionRequest request)
    {
      predict_calls++;
      if (_fails)
        throw new IllegalStateException("decoder failure");
      return Arrays.asList(new PredictionCandidate(_result, 1f));
    }

    public void reset_session()
    {
      reset_calls++;
    }

    public void close()
    {
      close_calls++;
    }
  }
}
