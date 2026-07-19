package juloo.keyboard2.prediction;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class PredictionDomainTest
{
  @Test
  public void request_copies_and_keeps_three_most_recent_preceding_words()
  {
    List<String> words = new ArrayList<>(Arrays.asList("one", "two", "three", "four"));
    PredictionRequest request = request(words, 4, 23L);

    words.set(3, "changed");

    assertEquals(Arrays.asList("two", "three", "four"), request.getPrecedingWords());
    try
    {
      request.getPrecedingWords().add("five");
      fail("preceding words must be unmodifiable");
    }
    catch (UnsupportedOperationException expected)
    {
    }
  }

  @Test
  public void request_rejects_non_positive_max_results()
  {
    assertInvalidMaxResults(0);
    assertInvalidMaxResults(-1);
  }

  @Test
  public void request_retains_generation_and_code_point_cursor_offset()
  {
    PredictionRequest request = request(Arrays.asList("vorher"), 3, 987654321L);

    assertEquals(3, request.getComposingCursorCodePoint());
    assertEquals(987654321L, request.getGeneration());
  }

  @Test
  public void request_retains_immutable_editor_prediction_policy()
  {
    EditorPredictionPolicy policy = EditorPredictionPolicy.from(
        android.text.InputType.TYPE_CLASS_TEXT, 0, null);
    PredictionRequest request = new PredictionRequest(
        "word", 4, java.util.Collections.emptyList(), false,
        "en", 3, 1L, policy);

    assertSame(policy, request.getEditorPredictionPolicy());
  }

  @Test
  public void request_rejects_cursor_past_bmp_text()
  {
    assertInvalidRequest("Grue", 5, Arrays.asList("word"), "gsw-CH");
  }

  @Test
  public void request_rejects_cursor_past_supplementary_code_point_text()
  {
    assertInvalidRequest("A\ud83d\ude00B", 4, Arrays.asList("word"), "gsw-CH");
  }

  @Test
  public void request_accepts_valid_supplementary_code_point_offset()
  {
    PredictionRequest request = new PredictionRequest(
        "A\ud83d\ude00B", 2, Arrays.asList("word"), false, "gsw-CH", 1, 9L,
        policy());

    assertEquals(2, request.getComposingCursorCodePoint());
  }

  @Test
  public void request_rejects_null_references_and_negative_cursor()
  {
    assertInvalidRequest(null, 0, Arrays.asList("word"), "gsw-CH");
    assertInvalidRequest("text", 0, null, "gsw-CH");
    assertInvalidRequest("text", 0, Arrays.asList("word"), null);
    assertInvalidRequest("text", -1, Arrays.asList("word"), "gsw-CH");
  }

  @Test
  public void candidate_retains_type_source_and_scores_and_is_immutable()
      throws Exception
  {
    PredictionCandidate candidate = candidate(CandidateType.CORRECTION, "lexicon");

    assertEquals("Gruezi", candidate.getText());
    assertEquals("gsw-CH", candidate.getLanguageTag());
    assertSame(CandidateType.CORRECTION, candidate.getType());
    assertEquals("lexicon", candidate.getSource());
    assertEquals(1.0, candidate.getLexicalScore(), 0.0);
    assertEquals(2.0, candidate.getContextScore(), 0.0);
    assertEquals(3.0, candidate.getTouchEditCost(), 0.0);
    assertEquals(4.0, candidate.getPersonalizationScore(), 0.0);
    assertEquals(5.0, candidate.getAutocorrectConfidence(), 0.0);
    assertTrue(Modifier.isFinal(PredictionCandidate.class.getModifiers()));
    for (Field field : PredictionCandidate.class.getDeclaredFields())
    {
      assertTrue(field.getName() + " must be private", Modifier.isPrivate(field.getModifiers()));
      assertTrue(field.getName() + " must be final", Modifier.isFinal(field.getModifiers()));
    }
  }

  @Test
  public void candidate_rejects_null_required_references()
  {
    assertInvalidCandidate(null, "gsw-CH", CandidateType.TYPED, "typed");
    assertInvalidCandidate("text", null, CandidateType.TYPED, "typed");
    assertInvalidCandidate("text", "gsw-CH", null, "typed");
    assertInvalidCandidate("text", "gsw-CH", CandidateType.TYPED, null);
  }

  @Test
  public void feedback_retains_candidate_identity_and_all_metadata()
  {
    PredictionCandidate candidate = candidate(CandidateType.COMPLETION, "model");
    PredictionFeedback feedback = new PredictionFeedback(
        FeedbackType.ACCEPTED, 42L, candidate, "Gruezi", 123456L);

    assertSame(FeedbackType.ACCEPTED, feedback.getType());
    assertEquals(42L, feedback.getGeneration());
    assertSame(candidate, feedback.getCandidate());
    assertEquals("Gruezi", feedback.getCommittedText());
    assertEquals(123456L, feedback.getTimestampMillis());
  }

  @Test
  public void feedback_allows_missing_candidate_only_for_committed_typed_text()
  {
    PredictionFeedback feedback = new PredictionFeedback(
        FeedbackType.COMMITTED, 1L, null, "typed", 2L);
    assertNull(feedback.getCandidate());

    try
    {
      new PredictionFeedback(
          FeedbackType.COMMITTED, 1L, candidate(CandidateType.TYPED, "typed"), "typed", 2L);
      fail("committed typed text must not have a candidate");
    }
    catch (IllegalArgumentException expected)
    {
    }

    try
    {
      new PredictionFeedback(FeedbackType.REJECTED, 1L, null, "typed", 2L);
      fail("non-committed feedback requires a candidate");
    }
    catch (NullPointerException expected)
    {
    }
  }

  @Test
  public void candidate_backed_feedback_retains_candidate_identity()
  {
    PredictionCandidate candidate = candidate(CandidateType.COMPLETION, "model");
    FeedbackType[] types = {
        FeedbackType.ACCEPTED, FeedbackType.REJECTED, FeedbackType.REVERTED
    };

    for (FeedbackType type : types)
    {
      PredictionFeedback feedback = new PredictionFeedback(type, 1L, candidate, "text", 2L);
      assertSame(candidate, feedback.getCandidate());
    }
  }

  @Test
  public void feedback_rejects_null_type_and_committed_text()
  {
    PredictionCandidate candidate = candidate(CandidateType.TYPED, "typed");
    try
    {
      new PredictionFeedback(null, 1L, candidate, "text", 2L);
      fail("type must not be null");
    }
    catch (NullPointerException expected)
    {
    }
    try
    {
      new PredictionFeedback(FeedbackType.ACCEPTED, 1L, candidate, null, 2L);
      fail("committed text must not be null");
    }
    catch (NullPointerException expected)
    {
    }
  }

  @Test
  public void engine_contract_exposes_reset_and_explicit_close() throws Exception
  {
    Method reset = PredictionEngine.class.getMethod("resetSession");
    Method close = PredictionEngine.class.getMethod("close");

    assertEquals(void.class, reset.getReturnType());
    assertEquals(void.class, close.getReturnType());
    assertEquals(PredictionEngine.class, close.getDeclaringClass());
    assertTrue(AutoCloseable.class.isAssignableFrom(PredictionEngine.class));
  }

  @Test
  public void enum_values_match_the_domain_contract()
  {
    assertArrayEquals(new CandidateType[] {
        CandidateType.TYPED,
        CandidateType.COMPLETION,
        CandidateType.CORRECTION,
        CandidateType.NEXT_WORD,
        CandidateType.SHORTCUT
    }, CandidateType.values());
    assertArrayEquals(new FeedbackType[] {
        FeedbackType.ACCEPTED,
        FeedbackType.COMMITTED,
        FeedbackType.REJECTED,
        FeedbackType.REVERTED
    }, FeedbackType.values());
  }

  private PredictionRequest request(List<String> precedingWords, int cursor, long generation)
  {
    return new PredictionRequest(
        "Grue", cursor, precedingWords, false, "gsw-CH", 5, generation,
        policy());
  }

  private void assertInvalidMaxResults(int maxResults)
  {
    try
    {
      new PredictionRequest(
          "text", 0, Arrays.asList("word"), false, "gsw-CH", maxResults, 1L,
          policy());
      fail("max results must be positive");
    }
    catch (IllegalArgumentException expected)
    {
    }
  }

  private void assertInvalidRequest(
      String text, int cursor, List<String> words, String languageTag)
  {
    try
    {
      new PredictionRequest(text, cursor, words, false, languageTag, 1, 1L,
          policy());
      fail("request must reject invalid input");
    }
    catch (NullPointerException | IllegalArgumentException expected)
    {
    }
  }

  private EditorPredictionPolicy policy()
  {
    return EditorPredictionPolicy.from(
        android.text.InputType.TYPE_CLASS_TEXT, 0, null);
  }

  private PredictionCandidate candidate(CandidateType type, String source)
  {
    return new PredictionCandidate(
        "Gruezi", "gsw-CH", type, source, 1.0, 2.0, 3.0, 4.0, 5.0);
  }

  private void assertInvalidCandidate(
      String text, String languageTag, CandidateType type, String source)
  {
    try
    {
      new PredictionCandidate(
          text, languageTag, type, source, 1.0, 2.0, 3.0, 4.0, 5.0);
      fail("candidate must reject null required references");
    }
    catch (NullPointerException expected)
    {
    }
  }
}
