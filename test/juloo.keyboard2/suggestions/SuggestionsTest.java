package juloo.keyboard2.suggestions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import juloo.keyboard2.prediction.CandidateType;
import juloo.keyboard2.prediction.ComposingContext;
import juloo.keyboard2.prediction.LegacyPredictionEngine;
import juloo.keyboard2.prediction.PredictionCandidate;
import juloo.keyboard2.prediction.PredictionEngine;
import juloo.keyboard2.prediction.PredictionEngineController;
import juloo.keyboard2.prediction.PredictionFeedback;
import juloo.keyboard2.prediction.PredictionRequest;
import org.junit.Test;
import static org.junit.Assert.*;

public class SuggestionsTest
{
  @Test
  public void adapter_keeps_first_three_unique_candidates_in_order()
  {
    PredictionCandidate first = candidate("One", LegacyPredictionEngine.SOURCE_CDICT);
    PredictionCandidate duplicate = candidate("one", LegacyPredictionEngine.SOURCE_PERSONAL);
    PredictionCandidate second = candidate("Two", LegacyPredictionEngine.SOURCE_PERSONAL);
    PredictionCandidate third = candidate("Three", LegacyPredictionEngine.SOURCE_TYPED);
    PredictionCandidate fourth = candidate("Four", LegacyPredictionEngine.SOURCE_CDICT);

    AdaptedCandidates adapted = PredictionCandidateAdapter.adapt(
        Arrays.asList(first, duplicate, second, third, fourth), 42L);

    assertArrayEquals(new String[] { "One", "Two", "Three" }, adapted.getSuggestions());
    assertArrayEquals(new boolean[] { false, true, false }, adapted.getPersonalSuggestions());
    assertEquals(3, adapted.getCount());
    assertEquals(Arrays.asList(first, second, third), adapted.getCandidates());
    assertEquals(42L, adapted.getGeneration());
  }

  @Test
  public void suggestions_adapts_engine_results_without_mixing_emoji()
  {
    CapturingEngine engine = new CapturingEngine(Arrays.asList(
        candidate("typed", LegacyPredictionEngine.SOURCE_TYPED),
        candidate("Personal", LegacyPredictionEngine.SOURCE_PERSONAL),
        candidate("Completion", LegacyPredictionEngine.SOURCE_CDICT)));
    Suggestions suggestions = new Suggestions(
        value -> {}, null, engine, () -> "de-ch");
    suggestions._enabled = true;
    suggestions.emoji_suggestion = "old emoji";

    suggestions.currently_typed_word(new ComposingContext(
        "typed", 5, Arrays.asList("before"), true, true));

    assertArrayEquals(new String[] { "typed", "Personal", "Completion" },
        suggestions.suggestions);
    assertArrayEquals(new boolean[] { false, true, false },
        suggestions.personal_suggestions);
    assertEquals(3, suggestions.count);
    assertNull(suggestions.emoji_suggestion);
    assertEquals("typed", engine.request.getComposingText());
    assertEquals(Arrays.asList("before"), engine.request.getPrecedingWords());
    assertEquals(3, engine.request.getMaxResults());
    assertEquals("de-CH", engine.request.getLanguageTag());
  }

  @Test
  public void request_falls_back_to_und_without_an_active_subtype_language_tag()
  {
    CapturingEngine engine = new CapturingEngine(Collections.emptyList());
    Suggestions suggestions = new Suggestions(
        value -> {}, null, engine, () -> null);
    suggestions._enabled = true;

    suggestions.currently_typed_word(new ComposingContext(
        "typed", 5, Collections.emptyList(), false, false));

    assertEquals("und", engine.request.getLanguageTag());
  }

  @Test
  public void adapted_candidates_snapshot_constructor_inputs()
  {
    String[] strings = { "Original", null, null };
    boolean[] personal = { true, false, false };
    PredictionCandidate original = candidate(
        "Original", LegacyPredictionEngine.SOURCE_PERSONAL);
    ArrayList<PredictionCandidate> candidates = new ArrayList<>();
    candidates.add(original);
    AdaptedCandidates adapted = new AdaptedCandidates(
        strings, personal, 1, candidates, 17L);

    strings[0] = "Changed";
    personal[0] = false;
    candidates.clear();

    assertArrayEquals(new String[] { "Original", null, null },
        adapted.getSuggestions());
    assertArrayEquals(new boolean[] { true, false, false },
        adapted.getPersonalSuggestions());
    assertEquals(Collections.singletonList(original), adapted.getCandidates());
    assertEquals(1, adapted.getCount());
    assertEquals(17L, adapted.getGeneration());
  }

  @Test
  public void suggestions_uses_monotonic_generations_and_preserves_typed_word_promotion()
  {
    CapturingEngine engine = new CapturingEngine(Collections.singletonList(
        candidate("typed", LegacyPredictionEngine.SOURCE_TYPED)));
    Suggestions suggestions = new Suggestions(value -> {}, null, engine);
    suggestions._enabled = true;
    ComposingContext context = new ComposingContext(
        "typed", 5, Collections.emptyList(), false, false);

    suggestions.currently_typed_word(context);
    long first = engine.request.getGeneration();
    suggestions.currently_typed_word(context);

    assertTrue(engine.request.getGeneration() > first);
    assertEquals("typed", suggestions.suggestions[0]);
    assertFalse(suggestions.is_current_generation_experimental());
    assertTrue(suggestions.can_auto_complete_current_candidate());
  }

  @Test
  public void experimental_result_records_source_and_disables_only_space_autocomplete()
  {
    CapturingEngine legacy = new CapturingEngine(Collections.singletonList(
        candidate("legacy", LegacyPredictionEngine.SOURCE_CDICT)));
    CapturingEngine experimental = new CapturingEngine(Collections.singletonList(
        candidate("experimental", "experimental")));
    PredictionEngineController controller =
        new PredictionEngineController(legacy, experimental, true);
    Suggestions suggestions = new Suggestions(
        value -> {}, null, controller, () -> "en");
    suggestions._enabled = true;

    suggestions.currently_typed_word(new ComposingContext(
        "word", 4, Collections.emptyList(), false, false));

    assertEquals("experimental", suggestions.suggestions[0]);
    assertTrue(suggestions.is_current_generation_experimental());
    assertFalse(suggestions.can_auto_complete_current_candidate());
  }

  @Test
  public void candidates_view_keeps_three_words_and_emoji_in_separate_fourth_slot()
  {
    Suggestions suggestions = new Suggestions(value -> {}, null,
        new CapturingEngine(Collections.emptyList()));
    suggestions.suggestions[0] = "One";
    suggestions.suggestions[1] = "Personal";
    suggestions.suggestions[2] = "Three";
    suggestions.personal_suggestions[1] = true;
    suggestions.count = 3;
    suggestions.emoji_suggestion = "emoji";
    String[] items = new String[4];
    boolean[] personal = new boolean[4];

    CandidatesView.copy_candidates(suggestions, items, personal);

    assertArrayEquals(new String[] { "One", "Personal", "Three", "emoji" }, items);
    assertArrayEquals(new boolean[] { false, true, false, false }, personal);
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
    Suggestions suggestions = new Suggestions(value -> {}, null,
        new CapturingEngine(Arrays.asList(
            candidate("Removed", LegacyPredictionEngine.SOURCE_PERSONAL),
            candidate("Kept", LegacyPredictionEngine.SOURCE_CDICT))));
    suggestions._enabled = true;
    suggestions.currently_typed_word(new ComposingContext(
        "word", 4, Collections.emptyList(), false, false));

    assertTrue(suggestions.remove_personal_candidate("Removed"));
    assertEquals(1, suggestions.count);
    assertEquals("Kept", suggestions.suggestions[0]);
    assertFalse(suggestions.personal_suggestions[0]);
  }

  @Test
  public void removing_personal_candidate_updates_retained_structured_candidates()
  {
    PredictionCandidate personal = candidate(
        "Personal", LegacyPredictionEngine.SOURCE_PERSONAL);
    PredictionCandidate kept = candidate("Kept", LegacyPredictionEngine.SOURCE_CDICT);
    CapturingEngine engine = new CapturingEngine(Arrays.asList(personal, kept));
    Suggestions suggestions = new Suggestions(value -> {}, null, engine);
    suggestions._enabled = true;
    suggestions.currently_typed_word(new ComposingContext(
        "word", 4, Collections.emptyList(), false, false));
    long generation = suggestions._adapted_candidates.getGeneration();

    assertTrue(suggestions.remove_personal_candidate("Personal"));

    assertEquals(Collections.singletonList(kept),
        suggestions._adapted_candidates.getCandidates());
    assertEquals(generation, suggestions._adapted_candidates.getGeneration());
    assertArrayEquals(new String[] { "Kept", null, null }, suggestions.suggestions);
  }

  private static PredictionCandidate candidate(String text, String source)
  {
    return new PredictionCandidate(text, "und", CandidateType.COMPLETION, source,
        0, 0, 0, 0, 0);
  }

  private static final class CapturingEngine implements PredictionEngine
  {
    final List<PredictionCandidate> result;
    PredictionRequest request;

    CapturingEngine(List<PredictionCandidate> result)
    {
      this.result = result;
    }

    public List<PredictionCandidate> predict(PredictionRequest value)
    {
      request = value;
      return result;
    }

    public void recordFeedback(PredictionFeedback feedback) {}
    public void resetSession() {}
    public void close() {}
  }
}
