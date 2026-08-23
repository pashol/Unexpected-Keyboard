package juloo.keyboard2;

import android.view.inputmethod.InputConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
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
          public void currently_typed_word(String text, boolean sentenceStart,
              List<String> precedingWords) {}
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
          public void currently_typed_word(String text, boolean sentenceStart,
              List<String> precedingWords) {}
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
  public void preceding_words_for_next_word_returns_last_three_in_order()
  {
    assertEquals(Arrays.asList("two", "three", "four"),
        CurrentlyTypedWord.preceding_words_for_next_word("one two three four ",
            true, ""));
  }

  @Test
  public void preceding_words_for_next_word_returns_none_for_empty_text_field()
  {
    assertEquals(Collections.emptyList(),
        CurrentlyTypedWord.preceding_words_for_next_word("", true, ""));
  }

  @Test
  public void preceding_words_for_next_word_requires_trailing_whitespace()
  {
    assertEquals(Collections.emptyList(),
        CurrentlyTypedWord.preceding_words_for_next_word("one two", true, ""));
  }

  @Test
  public void preceding_words_for_next_word_requires_known_context()
  {
    assertEquals(Collections.emptyList(),
        CurrentlyTypedWord.preceding_words_for_next_word("one two ", false, ""));
  }

  @Test
  public void preceding_words_for_next_word_requires_empty_composing_word()
  {
    assertEquals(Collections.emptyList(),
        CurrentlyTypedWord.preceding_words_for_next_word("one two ", true, "three"));
  }

  @Test
  public void preceding_words_for_next_word_handles_repeated_whitespace()
  {
    assertEquals(Arrays.asList("one", "two"),
        CurrentlyTypedWord.preceding_words_for_next_word("one\t  two\n ", true, ""));
  }

  @Test
  public void preceding_words_for_next_word_treats_punctuation_as_delimiters()
  {
    assertEquals(Arrays.asList("one", "two's", "three"),
        CurrentlyTypedWord.preceding_words_for_next_word("one, two's; three! ",
            true, ""));
  }

  @Test
  public void preceding_words_for_next_word_preserves_supplementary_letters()
  {
    assertEquals(Arrays.asList("\ud801\udc37bc", "def"),
        CurrentlyTypedWord.preceding_words_for_next_word("\ud801\udc37bc def ", true, ""));
  }

  @Test
  public void selected_text_produces_no_next_word_context()
  {
    final List<List<String>> precedingWords = new ArrayList<>();
    CurrentlyTypedWord word = new CurrentlyTypedWord(null,
        new CurrentlyTypedWord.Callback()
        {
          public void currently_typed_word(String text, boolean sentenceStart,
              List<String> words)
          {
            precedingWords.add(words);
          }
        });
    word._has_selection = true;
    word.set_current_word("one two ");

    assertEquals(Collections.emptyList(), precedingWords.get(0));
  }

  @Test
  public void cursor_moved_to_repeated_mid_text_whitespace_publishes_no_context()
      throws Exception
  {
    final List<List<String>> published = new ArrayList<>();
    CurrentlyTypedWord word = new CurrentlyTypedWord(null,
        new CurrentlyTypedWord.Callback()
        {
          public void currently_typed_word(String text, boolean sentenceStart,
              List<String> words)
          {
            published.add(words);
          }
        });
    Config config = config_with_initial_text("one  two ", "");
    word.started(config, input_connection("one  two ", 4));
    published.clear();

    word.selection_updated(9, 4, 4);

    assertEquals(1, published.size());
    assertEquals(Collections.emptyList(), published.get(0));
  }

  @Test
  public void cursor_move_within_word_publishes_empty_context() throws Exception
  {
    final List<List<String>> published = new ArrayList<>();
    CurrentlyTypedWord word = new CurrentlyTypedWord(null,
        new CurrentlyTypedWord.Callback()
        {
          public void currently_typed_word(String text, boolean sentenceStart,
              List<String> words)
          {
            published.add(words);
          }
        });
    word.started(config_with_initial_text("one ", ""), null);
    published.clear();
    word.typed("");
    assertEquals(Arrays.asList("one"), published.get(0));

    word.typed("two");
    published.clear();
    word.selection_updated(7, 6, 6);

    assertEquals(1, published.size());
    assertEquals(Collections.emptyList(), published.get(0));
  }

  Config config_with_initial_text(String before, String after) throws Exception
  {
    Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
    Field field = unsafeClass.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    Object unsafe = field.get(null);
    Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
    Config config = (Config)allocate.invoke(unsafe, Config.class);
    config.editor_config = new EditorConfig();
    config.editor_config.initial_text_before_cursor = before;
    config.editor_config.initial_text_after_cursor = after;
    config.editor_config.initial_sel_start = before.length();
    config.editor_config.initial_sel_end = before.length();
    return config;
  }

  InputConnection input_connection(final String text, final int cursor)
  {
    return (InputConnection)Proxy.newProxyInstance(
        InputConnection.class.getClassLoader(), new Class[] { InputConnection.class },
        new InvocationHandler()
        {
          public Object invoke(Object proxy, Method method, Object[] args)
          {
            if (method.getName().equals("getTextBeforeCursor"))
              return text.substring(0, cursor);
            Class<?> type = method.getReturnType();
            if (type == Boolean.TYPE) return false;
            if (type == Integer.TYPE) return 0;
            return null;
          }
        });
  }
}
