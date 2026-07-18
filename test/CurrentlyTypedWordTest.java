package juloo.keyboard2;

import org.junit.Test;
import static org.junit.Assert.*;

public class CurrentlyTypedWordTest
{
  @Test
  public void sentence_start_from_context_at_text_start()
  {
    assertFalse(CurrentlyTypedWord.sentence_start_from_context("", 0));
    assertTrue(CurrentlyTypedWord.sentence_start_from_context("hello", 5));
    assertFalse(CurrentlyTypedWord.sentence_start_from_context("", 1));
    assertFalse(CurrentlyTypedWord.sentence_start_from_context("hello", 20));
  }

  @Test
  public void sentence_start_from_context_requires_preceding_context()
  {
    String context = new String(new char[
        CurrentlyTypedWord.SENTENCE_CONTEXT_LENGTH]);
    assertFalse(CurrentlyTypedWord.sentence_start_from_context(context,
        CurrentlyTypedWord.SENTENCE_CONTEXT_LENGTH));
  }

  @Test
  public void sentence_start_is_false_for_null_editor_context()
  {
    CurrentlyTypedWord word = new CurrentlyTypedWord(null, null);
    word.set_current_word((CharSequence)null);
    assertFalse(word.sentence_start());
    word.set_current_word((android.view.inputmethod.SurroundingText)null);
    assertFalse(word.sentence_start());
  }

  @Test
  public void sentence_start_stays_false_after_typing_without_editor_context()
  {
    CurrentlyTypedWord word = new CurrentlyTypedWord(null,
        new CurrentlyTypedWord.Callback()
        {
          public void currently_typed_word(String text, boolean sentenceStart) {}
        });
    word._enabled = true;
    word.set_current_word((CharSequence)null);
    word.typed("a");
    assertFalse(word.sentence_start());
  }

  @Test
  public void sentence_start_requires_nontruncated_whitespace_context()
  {
    String truncated = new String(new char[
        CurrentlyTypedWord.SENTENCE_CONTEXT_LENGTH - 1]).replace('\0', ' ') + "a";
    assertFalse(CurrentlyTypedWord.sentence_start_from_context(truncated, 1));
    assertTrue(CurrentlyTypedWord.sentence_start_from_context("   a", 1));
  }

  @Test
  public void sentence_start_from_context_after_sentence_terminator()
  {
    assertTrue(CurrentlyTypedWord.sentence_start_from_context("Hello. World", 5));
    assertTrue(CurrentlyTypedWord.sentence_start_from_context("Hello! World", 5));
    assertTrue(CurrentlyTypedWord.sentence_start_from_context("Hello? World", 5));
    assertTrue(CurrentlyTypedWord.sentence_start_from_context("Hello\nWorld", 5));
  }

  @Test
  public void sentence_start_from_context_requires_sentence_boundary()
  {
    assertFalse(CurrentlyTypedWord.sentence_start_from_context("Hello, world", 5));
    assertFalse(CurrentlyTypedWord.sentence_start_from_context("Hello.world", 5));
    assertFalse(CurrentlyTypedWord.sentence_start_from_context("Hello world", 5));
  }
}
