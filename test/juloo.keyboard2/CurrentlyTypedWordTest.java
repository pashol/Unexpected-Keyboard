package juloo.keyboard2;

import org.junit.Test;
import static org.junit.Assert.*;
import static juloo.keyboard2.CurrentlyTypedWord.sentence_start_from_context;

public class CurrentlyTypedWordTest
{
  @Test
  public void field_start_is_sentence_start()
  {
    assertTrue(sentence_start_from_context("hello", 5));
  }

  @Test
  public void after_period_space_is_sentence_start()
  {
    assertTrue(sentence_start_from_context("das ist gut. h", 1));
  }

  @Test
  public void after_exclamation_is_sentence_start()
  {
    assertTrue(sentence_start_from_context("gut! h", 1));
  }

  @Test
  public void after_question_is_sentence_start()
  {
    assertTrue(sentence_start_from_context("gut? he", 2));
  }

  @Test
  public void after_newline_is_sentence_start()
  {
    assertTrue(sentence_start_from_context("gut\nhe", 2));
  }

  @Test
  public void mid_sentence_is_not_sentence_start()
  {
    assertFalse(sentence_start_from_context("das ist erd", 3));
  }

  @Test
  public void comma_is_not_sentence_start()
  {
    assertFalse(sentence_start_from_context("ja, na", 2));
  }

  @Test
  public void empty_word_is_not_sentence_start()
  {
    assertFalse(sentence_start_from_context("", 0));
  }

  @Test
  public void word_too_long_is_not_sentence_start()
  {
    String longWord = "abcdefghijklmnopqrstuvwxyzabcde"; // 31 chars
    assertFalse(sentence_start_from_context(longWord, 31));
  }

  @Test
  public void multiple_spaces_before_word_still_detects_sentence_start()
  {
    assertTrue(sentence_start_from_context("gut.  wo", 2));
  }
}
