package juloo.keyboard2;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

  @Test
  public void started_emits_one_complete_mid_word_context() throws Exception
  {
    final List<ComposingContext> received = new ArrayList<>();
    CurrentlyTypedWord word = new CurrentlyTypedWord(null,
        new CurrentlyTypedWord.Callback()
        {
          public void currently_typed_word(ComposingContext context)
          {
            received.add(context);
          }
        });
    EditorConfig editor = new EditorConfig();
    editor.initial_text_before_cursor = "one A\ud801\udc00";
    editor.initial_text_after_cursor = "B";
    editor.initial_sel_start = 7;
    editor.initial_sel_end = 7;

    word.started(config_with(editor), null);

    assertEquals(1, received.size());
    assertEquals("A\ud801\udc00B", received.get(0).composingText);
    assertEquals(2, received.get(0).composingCursorCodePoint);
    assertEquals(Arrays.asList("one"), received.get(0).precedingWords);
  }

  @Test
  public void started_does_not_emit_when_initial_context_is_unknown()
      throws Exception
  {
    final List<ComposingContext> received = new ArrayList<>();
    CurrentlyTypedWord word = new CurrentlyTypedWord(null,
        new CurrentlyTypedWord.Callback()
        {
          public void currently_typed_word(ComposingContext context)
          {
            received.add(context);
          }
        });
    EditorConfig editor = new EditorConfig();
    editor.initial_text_before_cursor = null;
    editor.initial_text_after_cursor = null;
    editor.initial_sel_start = 0;
    editor.initial_sel_end = 0;

    word.started(config_with(editor), null);

    assertTrue(received.isEmpty());
  }

  @Test
  public void local_cursor_movement_keeps_preceding_context_before_current_word()
  {
    final ComposingContext[] received = new ComposingContext[1];
    CurrentlyTypedWord word = capturing_typed_word(received);
    word.set_current_word("one hello");
    word._cursor = 9;

    word.selection_updated(9, 7, 7);
    word.typed("X");

    assertEquals("helXlo", received[0].composingText);
    assertEquals(4, received[0].composingCursorCodePoint);
    assertEquals(Arrays.asList("one"), received[0].precedingWords);
  }

  @Test
  public void local_cursor_movement_keeps_supplementary_context_synchronized()
  {
    final ComposingContext[] received = new ComposingContext[1];
    CurrentlyTypedWord word = capturing_typed_word(received);
    word.set_current_word("one A\ud801\udc00B");
    word._cursor = 8;

    word.selection_updated(8, 5, 5);
    word.typed("X");

    assertEquals("AX\ud801\udc00B", received[0].composingText);
    assertEquals(2, received[0].composingCursorCodePoint);
    assertEquals(Arrays.asList("one"), received[0].precedingWords);
    assertFalse(Character.isLowSurrogate(word._text_before_cursor.charAt(0)));
  }

  private CurrentlyTypedWord capturing_typed_word(
      final ComposingContext[] received)
  {
    CurrentlyTypedWord word = new CurrentlyTypedWord(null,
        new CurrentlyTypedWord.Callback()
        {
          public void currently_typed_word(ComposingContext context)
          {
            received[0] = context;
          }
        });
    word._enabled = true;
    return word;
  }

  private Config config_with(EditorConfig editor) throws Exception
  {
    Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
    Field singleton = unsafeClass.getDeclaredField("theUnsafe");
    singleton.setAccessible(true);
    Object unsafe = singleton.get(null);
    Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
    Config config = (Config)allocateInstance.invoke(unsafe, Config.class);
    config.editor_config = editor;
    return config;
  }

  private String repeat(char c, int count)
  {
    StringBuilder value = new StringBuilder(count);
    for (int i = 0; i < count; i++)
      value.append(c);
    return value.toString();
  }
}
