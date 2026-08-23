package juloo.keyboard2.prediction;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class PredictionSessionControllerTest
{
  private static final PredictionRequest REQUEST = new PredictionRequest(
      Collections.singletonList("hello"), 3, 1);

  @Test
  public void toggling_an_active_session_off_detaches_the_old_decoder_before_requests()
  {
    RecordingEngine decoder = new RecordingEngine("world");
    PredictionSessionController session = new PredictionSessionController();
    session.replace(new PredictionEngineController(true, decoder, new EmptyEngine()));

    assertEquals("world", session.predict(REQUEST).get(0).text());
    session.replace(null);

    assertTrue(session.predict(REQUEST).isEmpty());
    assertEquals(1, decoder.predict_calls);
    assertEquals(1, decoder.close_calls);
  }

  @Test
  public void replacing_for_a_subtype_change_closes_the_old_decoder()
  {
    RecordingEngine oldDecoder = new RecordingEngine("old");
    RecordingEngine newDecoder = new RecordingEngine("new");
    PredictionSessionController session = new PredictionSessionController();
    session.replace(new PredictionEngineController(true, oldDecoder, new EmptyEngine()));

    session.replace(new PredictionEngineController(true, newDecoder, new EmptyEngine()));

    assertEquals("new", session.predict(REQUEST).get(0).text());
    assertEquals(0, oldDecoder.predict_calls);
    assertEquals(1, oldDecoder.close_calls);
  }

  @Test
  public void finishing_an_input_view_closes_and_detaches_the_decoder()
  {
    RecordingEngine decoder = new RecordingEngine("world");
    PredictionSessionController session = new PredictionSessionController();
    session.replace(new PredictionEngineController(true, decoder, new EmptyEngine()));

    session.finish();

    assertNull(session.controller());
    assertTrue(session.predict(REQUEST).isEmpty());
    assertEquals(1, decoder.close_calls);
  }

  @Test
  public void development_fixture_is_limited_to_english_locale_tags()
  {
    assertTrue(DevelopmentPredictionPack.supports_locale("en"));
    assertTrue(DevelopmentPredictionPack.supports_locale("en-GB"));
    assertFalse(DevelopmentPredictionPack.supports_locale("fr-FR"));
  }

  private static final class RecordingEngine implements PredictionEngine
  {
    final List<PredictionCandidate> results;
    int predict_calls;
    int close_calls;

    RecordingEngine(String result)
    {
      results = Arrays.asList(new PredictionCandidate(result, 1f));
    }

    public List<PredictionCandidate> predict(PredictionRequest request)
    {
      predict_calls++;
      return results;
    }
    public void reset_session() {}
    public void close() { close_calls++; }
  }

  private static final class EmptyEngine implements PredictionEngine
  {
    public List<PredictionCandidate> predict(PredictionRequest request)
    {
      return Collections.emptyList();
    }
    public void reset_session() {}
    public void close() {}
  }
}
