package juloo.keyboard2.prediction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class PredictionDomainTest
{
  @Test
  public void request_keeps_the_last_three_preceding_words()
  {
    PredictionRequest request = new PredictionRequest(
        Arrays.asList("one", "two", "three", "four"), 3, 7);

    assertEquals(Arrays.asList("two", "three", "four"),
        request.preceding_words());
  }

  @Test
  public void request_preceding_words_are_defensive_and_immutable()
  {
    List<String> words = new ArrayList<String>(Arrays.asList("one", "two"));
    PredictionRequest request = new PredictionRequest(words, 3, 7);
    words.set(1, "changed");

    assertEquals(Arrays.asList("one", "two"), request.preceding_words());
    try
    {
      request.preceding_words().add("three");
      fail("Expected preceding words to be immutable");
    }
    catch (UnsupportedOperationException expected) {}
  }

  @Test(expected = IllegalArgumentException.class)
  public void request_rejects_nonpositive_max_candidates()
  {
    new PredictionRequest(Arrays.asList("one"), 0, 7);
  }

  @Test
  public void request_retains_generation()
  {
    PredictionRequest request = new PredictionRequest(
        Arrays.asList("one"), 3, 7);

    assertEquals(3, request.max_candidates());
    assertEquals(7, request.generation());
  }

  @Test
  public void candidate_exposes_text_and_score()
  {
    PredictionCandidate candidate = new PredictionCandidate("next", 0.75f);

    assertEquals("next", candidate.text());
    assertEquals(0.75f, candidate.score(), 0.0f);
  }

  @Test(expected = IllegalArgumentException.class)
  public void candidate_rejects_empty_text()
  {
    new PredictionCandidate("", 0.75f);
  }

  @Test(expected = IllegalArgumentException.class)
  public void candidate_rejects_null_text()
  {
    new PredictionCandidate(null, 0.75f);
  }

  @Test
  public void engine_is_an_autocloseable_prediction_contract()
      throws Exception
  {
    assertTrue(AutoCloseable.class.isAssignableFrom(PredictionEngine.class));
    assertEquals(List.class, PredictionEngine.class.getMethod("predict",
        PredictionRequest.class).getReturnType());
    assertEquals(void.class, PredictionEngine.class.getMethod("reset_session")
        .getReturnType());
    assertEquals(void.class, PredictionEngine.class.getMethod("close")
        .getReturnType());
  }
}
