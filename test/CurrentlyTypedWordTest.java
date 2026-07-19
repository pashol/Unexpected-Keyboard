package juloo.keyboard2;

import java.util.Arrays;
import juloo.keyboard2.prediction.ComposingContext;
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
          public void currently_typed_word(ComposingContext context) {}
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
  public void typed_preserves_word_and_cursor_without_editor_context()
  {
    CurrentlyTypedWord known = new_typed_word();
    known.set_current_word("hello");
    known._cursor = 5;
    CurrentlyTypedWord unknown = new_typed_word();
    unknown.set_current_word((CharSequence)null);
    unknown.typed("hello");

    type_at_and_move_from_cursor(known);
    type_at_and_move_from_cursor(unknown);
  }

  void type_at_and_move_from_cursor(CurrentlyTypedWord word)
  {
    word.selection_updated(5, 3, 3);
    word.typed("X");
    assertEquals("helXlo", word.get());
    assertEquals(-2, word.cursor_relative());
    word.selection_updated(4, 2, 2);
    word.typed("Y");
    assertEquals("heYlXlo", word.get());
    assertEquals(-4, word.cursor_relative());
  }

  CurrentlyTypedWord new_typed_word()
  {
    CurrentlyTypedWord word = new CurrentlyTypedWord(null,
        new CurrentlyTypedWord.Callback()
        {
          public void currently_typed_word(ComposingContext context) {}
        });
    word._enabled = true;
    return word;
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

  @Test
  public void callback_receives_complete_composing_context()
  {
    final ComposingContext[] received = new ComposingContext[1];
    CurrentlyTypedWord word = new CurrentlyTypedWord(null,
        new CurrentlyTypedWord.Callback()
        {
          public void currently_typed_word(ComposingContext context)
          {
            received[0] = context;
          }
        });
    word.set_current_word("Hallo. das isch A\ud801\udc00B");
    word._w_cursor = -1;
    word._text_before_cursor = "Hallo. das isch A\ud801\udc00";
    word.callback();

    assertEquals("A\ud801\udc00B", received[0].composingText);
    assertEquals(2, received[0].composingCursorCodePoint);
    assertEquals(Arrays.asList("Hallo", "das", "isch"),
        received[0].precedingWords);
    assertFalse(received[0].sentenceStart);
    assertTrue(received[0].contextKnown);
  }

  @Test
  public void callback_preserves_sentence_state_and_marks_unknown_context()
  {
    final ComposingContext[] received = new ComposingContext[1];
    CurrentlyTypedWord word = new CurrentlyTypedWord(null,
        new CurrentlyTypedWord.Callback()
        {
          public void currently_typed_word(ComposingContext context)
          {
            received[0] = context;
          }
        });
    word.set_current_word("Hallo. Nöd");
    assertTrue(received[0].sentenceStart);

    word._enabled = true;
    word.set_current_word((CharSequence)null);
    word.typed("nöd");
    assertFalse(received[0].contextKnown);
    assertTrue(received[0].precedingWords.isEmpty());
  }

  @Test
  public void local_typing_keeps_text_before_cursor_bounded()
  {
    CurrentlyTypedWord word = new_typed_word();
    word.set_current_word("");

    word.typed(repeat('a', CurrentlyTypedWord.SENTENCE_CONTEXT_LENGTH + 25));

    assertEquals(CurrentlyTypedWord.SENTENCE_CONTEXT_LENGTH,
        word._text_before_cursor.length());
  }

  @Test
  public void context_trimming_never_starts_with_a_low_surrogate()
  {
    CurrentlyTypedWord word = new_typed_word();
    word.set_current_word("");
    String typed = "a\ud801\udc00" +
        repeat('x', CurrentlyTypedWord.SENTENCE_CONTEXT_LENGTH - 1);

    word.typed(typed);

    assertTrue(word._text_before_cursor.length() <=
        CurrentlyTypedWord.SENTENCE_CONTEXT_LENGTH);
    assertFalse(Character.isLowSurrogate(word._text_before_cursor.charAt(0)));
    assertEquals(repeat('x', CurrentlyTypedWord.SENTENCE_CONTEXT_LENGTH - 1),
        word._text_before_cursor);
  }

  private String repeat(char c, int count)
  {
    StringBuilder value = new StringBuilder(count);
    for (int i = 0; i < count; i++)
      value.append(c);
    return value.toString();
  }
}
