package juloo.keyboard2.suggestions;

import org.junit.Test;
import static org.junit.Assert.*;

public class SuggestionsTest
{
  @Test
  public void deduplicates_case_insensitively()
  {
    assertTrue(Suggestions.already_in(
        new String[] { "Erde", null, null }, 1, "erde"));
    assertFalse(Suggestions.already_in(
        new String[] { "Erde", null, null }, 1, "Erdbeere"));
  }

  @Test
  public void promotes_typed_word_when_candidates_are_ambiguous()
  {
    String[] candidates = { "Erdbeere", "Erden", null };
    Suggestions.promote_typed_word(candidates, "erde");
    assertArrayEquals(new String[] { "erde", "Erdbeere", "Erden" },
        candidates);
  }

  @Test
  public void promotes_existing_typed_word_with_its_stored_casing()
  {
    String[] candidates = { "Erdbeere", "Erde", null };
    Suggestions.promote_typed_word(candidates, "erde");
    assertArrayEquals(new String[] { "Erde", "Erdbeere", null }, candidates);
  }

  @Test
  public void keeps_single_unambiguous_completion()
  {
    String[] candidates = { "Grosswangen", null, null };
    Suggestions.promote_typed_word(candidates, "Grosswan");
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
    Suggestions.promote_typed_word(candidates, "Grosswan");
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
  }

  @Test
  public void keeps_personal_provenance_when_promoting_a_candidate()
  {
    String[] candidates = { "System", "Personal", null };
    boolean[] personal = { false, true, false };

    Suggestions.promote_typed_word(candidates, personal, "personal");

    assertArrayEquals(new String[] { "Personal", "System", null }, candidates);
    assertArrayEquals(new boolean[] { true, false, false }, personal);
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
}
