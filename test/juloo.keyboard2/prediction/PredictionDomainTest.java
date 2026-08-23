package juloo.keyboard2.prediction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class PredictionDomainTest
{
  @Test
  public void request_caps_and_defensively_copies_preceding_words()
  {
    List<String> words = new ArrayList<>(Arrays.asList(
        "one", "two", "three", "four"));
    PredictionRequest request = new PredictionRequest("typed", 2, words, 3, 7L);
    words.set(0, "changed");

    assertEquals(Arrays.asList("one", "two", "three"),
        request.preceding_words());
    try
    {
      request.preceding_words().add("another");
      fail("preceding words must be immutable");
    }
    catch (UnsupportedOperationException expected) {}
  }

  @Test
  public void request_requires_a_positive_maximum_count()
  {
    try
    {
      new PredictionRequest("typed", 0, Arrays.<String>asList(), 0, 1L);
      fail("maximum count must be positive");
    }
    catch (IllegalArgumentException expected) {}
  }

  @Test
  public void request_retains_generation_and_code_point_cursor_offset()
  {
    PredictionRequest request = new PredictionRequest("typed", -1,
        Arrays.<String>asList(), 2, 99L);

    assertEquals(-1, request.cursor_code_point_offset());
    assertEquals(99L, request.generation());
  }

  @Test
  public void candidate_is_immutable()
  {
    PredictionCandidate candidate = new PredictionCandidate("hello", "en",
        PredictionCandidate.Type.COMPLETION, 1.0f, 2.0f, 3.0f, 4.0f, 5.0f,
        "dictionary");

    assertEquals("hello", candidate.text());
    assertEquals("en", candidate.locale());
    assertEquals(PredictionCandidate.Type.COMPLETION, candidate.type());
    assertEquals(1.0f, candidate.lexical_score(), 0.0f);
    assertEquals(2.0f, candidate.context_score(), 0.0f);
    assertEquals(3.0f, candidate.touch_or_edit_cost(), 0.0f);
    assertEquals(4.0f, candidate.personalization_score(), 0.0f);
    assertEquals(5.0f, candidate.autocorrect_confidence(), 0.0f);
    assertEquals("dictionary", candidate.source());
  }
}
