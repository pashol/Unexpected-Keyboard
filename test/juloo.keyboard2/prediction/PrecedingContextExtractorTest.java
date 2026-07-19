package juloo.keyboard2.prediction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class PrecedingContextExtractorTest
{
  @Test
  public void extracts_zero_to_three_previous_words()
  {
    assertEquals(Collections.emptyList(), extract("cur", 3));
    assertEquals(Arrays.asList("one"), extract("one cur", 3));
    assertEquals(Arrays.asList("one", "two"), extract("one two cur", 3));
    assertEquals(Arrays.asList("one", "two", "three"),
        extract("one two three cur", 3));
  }

  @Test
  public void keeps_only_three_most_recent_words_in_chronological_order()
  {
    assertEquals(Arrays.asList("two", "three", "four"),
        extract("one two three four cur", 3));
  }

  @Test
  public void handles_punctuation_and_repeated_whitespace()
  {
    assertEquals(Arrays.asList("one", "two", "three"),
        extract("one,  two...\n\tthree   cur", 3));
  }

  @Test
  public void keeps_apostrophes_inside_words()
  {
    assertEquals(Arrays.asList("l'ami", "d'Artagnan"),
        extract("l'ami d'Artagnan cur", 3));
  }

  @Test
  public void handles_swiss_german_unicode_text()
  {
    assertEquals(Arrays.asList("das", "isch"), extract("das isch nöd", 3));
  }

  @Test
  public void handles_supplementary_unicode_letters_without_splitting_them()
  {
    String deseretLetter = "\ud801\udc00";
    assertEquals(Arrays.asList("a" + deseretLetter + "b"),
        extract("a" + deseretLetter + "b cur", 3));
  }

  @Test
  public void excludes_the_current_token_at_end_and_mid_token()
  {
    assertEquals(Arrays.asList("one", "two"), extract("one two current", 7));
    assertEquals(Arrays.asList("one", "two"), extract("one two cur", 3));
  }

  @Test
  public void empty_or_only_current_truncated_context_has_no_previous_words()
  {
    assertEquals(Collections.emptyList(), extract("", 0));
    assertEquals(Collections.emptyList(), extract("partial", 7));
  }

  @Test
  public void composing_context_is_bounded_immutable_and_defensively_copied()
  {
    List<String> words = new ArrayList<>(
        Arrays.asList("one", "two", "three", "four"));
    ComposingContext context = new ComposingContext(
        "A\ud83d\ude00B", 2, words, true, true);

    words.set(3, "changed");

    assertEquals("A\ud83d\ude00B", context.composingText);
    assertEquals(2, context.composingCursorCodePoint);
    assertEquals(Arrays.asList("two", "three", "four"), context.precedingWords);
    assertTrue(context.sentenceStart);
    assertTrue(context.contextKnown);
    try
    {
      context.precedingWords.add("five");
      fail("preceding words must be unmodifiable");
    }
    catch (UnsupportedOperationException expected)
    {
    }
  }

  @Test
  public void composing_context_validates_required_values_and_code_point_cursor()
  {
    assertInvalidContext(null, 0, Collections.<String>emptyList());
    assertInvalidContext("text", 0, null);
    assertInvalidContext("text", -1, Collections.<String>emptyList());
    assertInvalidContext("A\ud83d\ude00B", 4, Collections.<String>emptyList());
  }

  private List<String> extract(String textBeforeCursor, int composingPrefixChars)
  {
    return PrecedingContextExtractor.extract(textBeforeCursor, composingPrefixChars);
  }

  private void assertInvalidContext(String text, int cursor, List<String> words)
  {
    try
    {
      new ComposingContext(text, cursor, words, false, false);
      fail("context must reject invalid input");
    }
    catch (NullPointerException | IllegalArgumentException expected)
    {
    }
  }
}
