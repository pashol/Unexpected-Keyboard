package juloo.keyboard2.suggestions;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import juloo.keyboard2.prediction.PredictionCandidate;
import juloo.keyboard2.prediction.PredictionEngine;
import juloo.keyboard2.prediction.PredictionEngineController;
import juloo.keyboard2.prediction.PredictionRequest;
import org.junit.Test;
import static org.junit.Assert.*;

public class SuggestionsTest
{
  @Test
  public void two_argument_currently_typed_word_is_source_compatible()
  {
    new Suggestions(null, null).currently_typed_word("word", false);
  }

  @Test
  public void deduplicates_case_insensitively()
  {
    assertTrue(Suggestions.already_in(
        new String[] { "Erde", null, null }, 1, "erde"));
    assertFalse(Suggestions.already_in(
        new String[] { "Erde", null, null }, 1, "Erdbeere"));
  }

  @Test
  public void places_missing_typed_word_last_when_candidates_are_ambiguous()
  {
    String[] candidates = { "Erdbeere", "Erden", null };
    Suggestions.place_typed_word_last(candidates, "erde");
    assertArrayEquals(new String[] { "Erdbeere", "Erden", "erde" },
        candidates);
  }

  @Test
  public void places_existing_typed_word_last_with_its_stored_casing()
  {
    String[] candidates = { "Erdbeere", "Erde", null };
    Suggestions.place_typed_word_last(candidates, "erde");
    assertArrayEquals(new String[] { "Erdbeere", "Erde", null }, candidates);
  }

  @Test
  public void keeps_single_unambiguous_completion()
  {
    String[] candidates = { "Grosswangen", null, null };
    Suggestions.place_typed_word_last(candidates, "Grosswan");
    assertArrayEquals(new String[] { "Grosswangen", null, null }, candidates);
  }

  @Test
  public void alternates_only_the_first_character_case()
  {
    assertEquals("Erde", Suggestions.alternate_first_character("erde"));
    assertEquals("erde", Suggestions.alternate_first_character("Erde"));
  }

  @Test
  public void falls_back_to_alternate_case_only_without_a_prefix_node()
  {
    assertTrue(Suggestions.should_lookup_alternate_case(0));
    assertFalse(Suggestions.should_lookup_alternate_case(1));
  }

  @Test
  public void capitalizes_only_word_candidates_at_sentence_start()
  {
    String[] candidates = { "erde", "erdbeere", null };
    String emoji = "earth";
    Suggestions.capitalize_results(candidates);
    assertArrayEquals(new String[] { "Erde", "Erdbeere", null }, candidates);
    assertEquals("earth", emoji);
  }

  @Test
  public void keeps_emoji_outside_word_candidate_promotion()
  {
    String[] candidates = { "Grosswangen", null, null };
    String emoji = "map";
    Suggestions.place_typed_word_last(candidates, "Grosswan");
    assertEquals("Grosswangen", candidates[0]);
    assertEquals("map", emoji);
  }

  @Test
  public void prepends_personal_candidates_without_duplicate_cdict_words()
  {
    String[] candidates = { "Erde", "Erden", "Erdbeere" };
    boolean[] personal = new boolean[Suggestions.MAX_COUNT];
    Suggestions.prepend_personal_candidates(candidates, personal,
        new String[] { "erde", "Erdling", "Erdung" });
    assertArrayEquals(new String[] { "Erdling", "Erde", "Erden" }, candidates);
    assertArrayEquals(new boolean[] { true, false, false }, personal);
  }

  @Test
  public void removes_only_enabled_personal_word_candidates()
      throws Exception
  {
    java.io.File directory = java.nio.file.Files.createTempDirectory("dictionary").toFile();
    UserDictionary dictionary = new UserDictionary(
        new java.io.File(directory, "user_words.txt"));
    dictionary.add("Personal");

    assertTrue(CandidatesView.can_remove_personal_candidate(true, 0, true));
    assertFalse(CandidatesView.can_remove_personal_candidate(false, 0, true));
    assertFalse(CandidatesView.can_remove_personal_candidate(true, 3, true));
    assertFalse(CandidatesView.can_remove_personal_candidate(true, 0, false));
    assertFalse(CandidatesView.can_remove_personal_candidate(true, 0, true,
        Suggestions.CandidateType.NEXT_WORD));
  }

  @Test
  public void keeps_candidate_metadata_when_placing_a_candidate_last()
  {
    String[] candidates = { "Completion", "Typed", "Other" };
    boolean[] personal = { false, true, false };
    Suggestions.CandidateType[] types = {
      Suggestions.CandidateType.COMPLETION, Suggestions.CandidateType.NEXT_WORD,
      Suggestions.CandidateType.COMPLETION
    };

    Suggestions.place_typed_word_last(candidates, personal, types, "typed");

    assertArrayEquals(new String[] { "Completion", "Other", "Typed" }, candidates);
    assertArrayEquals(new boolean[] { false, false, true }, personal);
    assertArrayEquals(new Suggestions.CandidateType[] {
        Suggestions.CandidateType.COMPLETION, Suggestions.CandidateType.COMPLETION,
        Suggestions.CandidateType.NEXT_WORD }, types);
  }

  @Test
  public void keeps_best_typed_word_centered_instead_of_forcing_a_correction()
  {
    String[] candidates = { "Typed", "Completion", "Other" };
    Suggestions.place_typed_word_last(candidates, "typed");
    assertArrayEquals(new String[] { "Typed", "Completion", "Other" }, candidates);
  }

  @Test
  public void does_not_confirm_candidate_removal_when_persistence_fails()
      throws Exception
  {
    java.io.File directory = java.nio.file.Files.createTempDirectory("dictionary").toFile();
    UserDictionary dictionary = new UserDictionary(
        new java.io.File(directory, "user_words.txt"));
    dictionary.add("Personal");
    assertTrue(directory.setWritable(false, false));
    try
    {
      assertFalse(CandidatesView.remove_personal_candidate(dictionary, "Personal"));
      assertTrue(dictionary.contains("Personal"));
    }
    finally
    {
      directory.setWritable(true, false);
    }
  }

  @Test
  public void rejects_long_press_when_slot_replaced_before_timeout()
  {
    assertTrue(CandidatesView.matches_long_press_candidate(
        "Personal", true, "Personal", true));
    assertFalse(CandidatesView.matches_long_press_candidate(
        "Personal", true, "Replacement", true));
    assertFalse(CandidatesView.matches_long_press_candidate(
        "Personal", true, "Personal", false));
  }

  @Test
  public void removing_personal_candidate_compacts_active_suggestions()
  {
    Suggestions suggestions = new Suggestions(new Suggestions.Callback()
    {
      public void set_suggestions(Suggestions value) {}
    }, null);
    suggestions.suggestions[0] = "Removed";
    suggestions.suggestions[1] = "Kept";
    suggestions.personal_suggestions[0] = true;
    suggestions.count = 2;

    assertTrue(suggestions.remove_personal_candidate("Removed"));
    assertEquals(1, suggestions.count);
    assertEquals("Kept", suggestions.suggestions[0]);
    assertFalse(suggestions.personal_suggestions[0]);
  }

  @Test
  public void next_word_candidates_are_typed_nonpersonal_and_have_no_emoji()
  {
    Suggestions suggestions = new Suggestions(null, null);
    suggestions.emoji_suggestion = "emoji";
    suggestions.set_candidate_case(Suggestions.CandidateCase.UPPER);

    suggestions.set_next_word_candidates(Arrays.asList(
        new PredictionCandidate("world", 1f),
        new PredictionCandidate("there", .5f)));

    assertArrayEquals(new String[] { "WORLD", "THERE", null }, suggestions.suggestions);
    assertArrayEquals(new Suggestions.CandidateType[] {
        Suggestions.CandidateType.NEXT_WORD, Suggestions.CandidateType.NEXT_WORD,
        Suggestions.CandidateType.COMPLETION }, suggestions.types);
    assertArrayEquals(new boolean[] { false, false, false }, suggestions.personal_suggestions);
    assertEquals(2, suggestions.count);
    assertNull(suggestions.emoji_suggestion);
  }

  @Test
  public void applies_title_case_to_word_candidates()
  {
    String[] candidates = { "hello", "WORLD", null };

    Suggestions.apply_candidate_case(candidates, Suggestions.CandidateCase.TITLE);

    assertArrayEquals(new String[] { "Hello", "World", null }, candidates);
  }

  @Test
  public void applies_title_case_to_supplementary_initial_with_root_locale()
  {
    Locale original_locale = Locale.getDefault();
    try
    {
      Locale.setDefault(new Locale("tr", "TR"));
      String[] candidates = { "\uD801\uDC28ELLO", "istanbul", null };

      Suggestions.apply_candidate_case(candidates, Suggestions.CandidateCase.TITLE);

      assertArrayEquals(new String[] { "\uD801\uDC00ello", "Istanbul", null },
          candidates);
    }
    finally
    {
      Locale.setDefault(original_locale);
    }
  }

  @Test
  public void applies_upper_case_to_word_candidates()
  {
    String[] candidates = { "Hello", "world", null };

    Suggestions.apply_candidate_case(candidates, Suggestions.CandidateCase.UPPER);

    assertArrayEquals(new String[] { "HELLO", "WORLD", null }, candidates);
  }

  @Test
  public void preserves_dictionary_casing_for_default_candidate_case()
  {
    String[] candidates = { "iPhone", "McDonald", null };

    Suggestions.apply_candidate_case(candidates, Suggestions.CandidateCase.DEFAULT);

    assertArrayEquals(new String[] { "iPhone", "McDonald", null }, candidates);
  }

  @Test
  public void reports_only_real_candidate_case_changes()
  {
    Suggestions suggestions = new Suggestions(null, null);

    assertTrue(suggestions.set_candidate_case(Suggestions.CandidateCase.TITLE));
    assertFalse(suggestions.set_candidate_case(Suggestions.CandidateCase.TITLE));
    assertTrue(suggestions.set_candidate_case(Suggestions.CandidateCase.UPPER));
  }

  @Test
  public void keeps_completion_order_when_applying_title_case()
  {
    String[] candidates = { "best", "middle", null };

    Suggestions.place_typed_word_last(candidates, "typed");
    Suggestions.apply_candidate_case(candidates, Suggestions.CandidateCase.TITLE);

    assertArrayEquals(new String[] { "Best", "Middle", "Typed" }, candidates);
  }

  @Test
  public void keeps_completion_order_when_applying_upper_case()
  {
    String[] candidates = { "best", "middle", null };

    Suggestions.place_typed_word_last(candidates, "typed");
    Suggestions.apply_candidate_case(candidates, Suggestions.CandidateCase.UPPER);

    assertArrayEquals(new String[] { "BEST", "MIDDLE", "TYPED" }, candidates);
  }

  @Test
  public void whitespace_boundary_displays_injected_next_word_candidates()
  {
    Suggestions suggestions = enabled_suggestions(new RecordingEngine("world", "there"));

    suggestions.currently_typed_word("", false, Collections.singletonList("hello"));

    assertArrayEquals(new String[] { "world", "there", null }, suggestions.suggestions);
    assertEquals(Suggestions.CandidateType.NEXT_WORD, suggestions.types[0]);
  }

  @Test
  public void nonempty_word_keeps_legacy_completion_path()
  {
    RecordingEngine engine = new RecordingEngine("world");
    Suggestions suggestions = enabled_suggestions(engine);

    suggestions.currently_typed_word("h", false, Collections.singletonList("hello"));

    assertEquals(0, suggestions.count);
    assertEquals(0, engine.predict_calls);
  }

  @Test
  public void decoder_failure_clears_next_word_candidates_for_the_session()
  {
    RecordingEngine failing = new RecordingEngine();
    failing.fails = true;
    Suggestions suggestions = enabled_suggestions(failing);

    suggestions.currently_typed_word("", false, Collections.singletonList("hello"));

    assertEquals(0, suggestions.count);
    assertEquals(1, failing.predict_calls);
  }

  @Test
  public void context_refresh_discards_a_stale_next_word_response()
  {
    ReentrantEngine engine = new ReentrantEngine();
    Suggestions suggestions = enabled_suggestions(engine);
    engine.suggestions = suggestions;

    suggestions.currently_typed_word("", false, Collections.singletonList("hello"));

    assertEquals(0, suggestions.count);
  }

  private static Suggestions enabled_suggestions(PredictionEngine experimental)
  {
    PredictionEngine empty = new RecordingEngine();
    Suggestions suggestions = new Suggestions(new Suggestions.Callback()
    {
      public void set_suggestions(Suggestions value) {}
    }, null, new PredictionEngineController(true, experimental, empty));
    suggestions._enabled = true;
    return suggestions;
  }

  private static class RecordingEngine implements PredictionEngine
  {
    final java.util.List<PredictionCandidate> results = new java.util.ArrayList<PredictionCandidate>();
    boolean fails;
    int predict_calls;

    RecordingEngine(String... words)
    {
      for (String word : words)
        results.add(new PredictionCandidate(word, 1f));
    }

    public java.util.List<PredictionCandidate> predict(PredictionRequest request)
    {
      predict_calls++;
      if (fails)
        throw new IllegalStateException("decoder failure");
      return results;
    }

    public void reset_session() {}
    public void close() {}
  }

  private static final class ReentrantEngine extends RecordingEngine
  {
    Suggestions suggestions;
    boolean refreshed;

    ReentrantEngine()
    {
      super("world");
    }

    @Override public java.util.List<PredictionCandidate> predict(PredictionRequest request)
    {
      java.util.List<PredictionCandidate> result = super.predict(request);
      if (!refreshed)
      {
        refreshed = true;
        suggestions.currently_typed_word("x", false, Collections.<String>emptyList());
      }
      return result;
    }
  }

  @Test
  public void removing_personal_completion_keeps_candidate_types_aligned()
  {
    Suggestions suggestions = new Suggestions(new Suggestions.Callback()
    {
      public void set_suggestions(Suggestions value) {}
    }, null);
    suggestions.suggestions[0] = "Removed";
    suggestions.suggestions[1] = "Next";
    suggestions.personal_suggestions[0] = true;
    suggestions.types[0] = Suggestions.CandidateType.COMPLETION;
    suggestions.types[1] = Suggestions.CandidateType.NEXT_WORD;
    suggestions.count = 2;

    assertTrue(suggestions.remove_personal_candidate("Removed"));
    assertEquals("Next", suggestions.suggestions[0]);
    assertEquals(Suggestions.CandidateType.NEXT_WORD, suggestions.types[0]);
    assertEquals(Suggestions.CandidateType.COMPLETION, suggestions.types[1]);
  }
}
