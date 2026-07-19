package juloo.keyboard2;

import android.os.Handler;
import android.view.inputmethod.InputConnection;
import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.junit.Test;
import juloo.keyboard2.suggestions.UserDictionary;
import static org.junit.Assert.*;

public class KeyEventHandlerTest
{
  @Test
  public void cycle_word_case_rotates_lower_title_upper()
  {
    assertEquals("Hello", KeyEventHandler.cycle_word_case("hello"));
    assertEquals("HELLO", KeyEventHandler.cycle_word_case("Hello"));
    assertEquals("hello", KeyEventHandler.cycle_word_case("HELLO"));
  }

  @Test
  public void cycle_word_case_rotates_supplementary_plane_letters()
  {
    String lower = "\uD801\uDC28\uD801\uDC29";
    String title = "\uD801\uDC00\uD801\uDC29";
    String upper = "\uD801\uDC00\uD801\uDC01";
    assertEquals(title, KeyEventHandler.cycle_word_case(lower));
    assertEquals(upper, KeyEventHandler.cycle_word_case(title));
    assertEquals(lower, KeyEventHandler.cycle_word_case(upper));
  }

  @Test
  public void punctuation_auto_spacing_accepts_only_single_supported_punctuation()
  {
    assertTrue(KeyEventHandler.is_auto_spacing_punctuation("."));
    assertTrue(KeyEventHandler.is_auto_spacing_punctuation(":"));
    assertFalse(KeyEventHandler.is_auto_spacing_punctuation("-"));
    assertFalse(KeyEventHandler.is_auto_spacing_punctuation(".."));
  }

  @Test
  public void learning_requires_enabled_unknown_letter_word_and_delimiter()
  {
    assertTrue(KeyEventHandler.should_learn_word(true, false, "unusual", " "));
    assertFalse(KeyEventHandler.should_learn_word(false, false, "unusual", " "));
    assertFalse(KeyEventHandler.should_learn_word(true, true, "unusual", " "));
    assertFalse(KeyEventHandler.should_learn_word(true, false, "two", "x"));
    assertFalse(KeyEventHandler.should_learn_word(true, false, "a-thing", "."));
  }

  @Test
  public void learning_recognizes_alternate_first_character_case()
  {
    KeyEventHandler.WordLookup lookup = new KeyEventHandler.WordLookup()
    {
      public boolean found(String word) { return word.equals("hello"); }
    };
    assertTrue(KeyEventHandler.dictionary_knows_word(lookup, "hello"));
    assertTrue(KeyEventHandler.dictionary_knows_word(lookup, "Hello"));
  }

  @Test
  public void empty_word_is_not_looked_up_or_learned_at_punctuation()
  {
    final int[] lookups = { 0 };
    KeyEventHandler.WordLookup lookup = new KeyEventHandler.WordLookup()
    {
      public boolean found(String word) { lookups[0]++; return false; }
    };

    assertFalse(KeyEventHandler.dictionary_knows_word(lookup, ""));
    assertEquals(0, lookups[0]);
    assertFalse(KeyEventHandler.should_learn_word(true, false, "", "."));
  }

  @Test
  public void autocomplete_is_skipped_once_after_undo()
  {
    assertFalse(KeyEventHandler.should_autocomplete_space(true));
    assertTrue(KeyEventHandler.should_autocomplete_space(false));
  }

  @Test
  public void entering_suggestion_does_not_append_a_space()
  {
    FakeInputConnection connection = new FakeInputConnection();
    KeyEventHandler handler = new KeyEventHandler(new Receiver(connection.connection), null);

    handler.suggestion_entered("word");

    assertEquals("word", connection.text());
  }

  @Test
  public void manual_shift_latch_leaves_word_unchanged_before_supplementary_letter()
  {
    FakeInputConnection connection = new FakeInputConnection("hello\uD801\uDC00");
    connection.cursor = 5;
    KeyEventHandler handler = new_handler(connection);
    handler._typedword._enabled = true;
    handler._typedword.set_current_word("hello");

    handler.key_down(KeyValue.SHIFT, false);
    handler.mods_changed(Pointers.Modifiers.EMPTY.with_extra_mod(KeyValue.SHIFT), true);

    assertEquals("hello\uD801\uDC00", connection.text());
    assertEquals(2, connection.after_cursor_request);
    assertTrue(handler._manual_shift_latched);
    assertEquals(0, ((Receiver)handler._recv).shift_changes);
  }

  @Test
  public void successful_manual_shift_cycle_clears_physical_latch()
  {
    FakeInputConnection connection = new FakeInputConnection("hello");
    KeyEventHandler handler = new_handler(connection);
    handler._typedword._enabled = true;
    handler._typedword.set_current_word("hello");
    Receiver receiver = (Receiver)handler._recv;

    handler.key_down(KeyValue.SHIFT, false);
    handler.mods_changed(Pointers.Modifiers.EMPTY.with_extra_mod(KeyValue.SHIFT), true);

    assertEquals("Hello", connection.text());
    assertTrue(receiver.shift_latch_cleared);
  }

  @Test
  public void auto_space_decision_uses_current_preference_and_editor_gate()
  {
    assertTrue(KeyEventHandler.should_auto_space_after_punctuation(true, false, "."));
    assertFalse(KeyEventHandler.should_auto_space_after_punctuation(false, false, "."));
    assertFalse(KeyEventHandler.should_auto_space_after_punctuation(true, true, "."));
  }

  @Test
  public void punctuation_plan_removes_only_automatic_space_and_adds_one_space()
  {
    assertEquals("! ", KeyEventHandler.auto_space_text("!", false));
    assertEquals("!", KeyEventHandler.auto_space_text("!", true));
    assertTrue(KeyEventHandler.should_remove_auto_space(true, "!"));
    assertFalse(KeyEventHandler.should_remove_auto_space(false, "!"));
  }

  @Test
  public void handler_removes_prior_automatic_space_before_next_punctuation()
  {
    FakeInputConnection connection = new FakeInputConnection();
    KeyEventHandler handler = new_handler(connection);

    handler.send_text(".", true);
    handler.send_text("!", true);

    assertEquals(".! ", connection.text());
  }

  @Test
  public void cursor_movement_clears_automatic_space_before_punctuation()
  {
    FakeInputConnection connection = new FakeInputConnection();
    KeyEventHandler handler = new_handler(connection);

    handler.send_text(".", true);
    connection.cursor--;
    handler.selection_updated(2, 1, 1);
    handler.send_text("!", true);

    assertFalse(handler._auto_space_inserted);
    assertEquals(".! ", connection.text());
  }

  @Test
  public void learning_adds_unknown_words_only_when_enabled() throws Exception
  {
    UserDictionary dictionary = dictionary();
    assertTrue(KeyEventHandler.learn_word(dictionary, true, false, "unusual", " "));
    assertTrue(dictionary.contains("unusual"));
    assertFalse(KeyEventHandler.learn_word(dictionary, false, false, "disabled", " "));
    assertFalse(KeyEventHandler.learn_word(dictionary, true, true, "known", " "));
  }

  @Test
  public void undo_state_allows_original_word_to_be_learned_on_next_delimiter()
      throws Exception
  {
    UserDictionary dictionary = dictionary();
    assertTrue(KeyEventHandler.should_learn_after_autocomplete_undo(true, "typed", "."));
    assertTrue(KeyEventHandler.learn_word(dictionary, true, false, "typed", "."));
    assertTrue(dictionary.contains("typed"));
  }

  @Test
  public void handler_reads_refreshed_auto_space_preference_and_editor_exclusion()
      throws Exception
  {
    FakeInputConnection connection = new FakeInputConnection("word");
    KeyEventHandler handler = new_handler(connection);
    handler.refresh_typing_config(true, false, false);
    handler.send_text(".");
    handler.refresh_typing_config(false, false, false);
    handler.send_text("!");
    handler.refresh_typing_config(true, true, false);
    handler.send_text("?");

    assertEquals("word. !?", connection.text());
  }

  @Test
  public void handler_learns_only_enabled_unknown_words_and_learns_after_undo()
      throws Exception
  {
    UserDictionary dictionary = dictionary();
    set_user_dictionary_instance(dictionary);
    FakeInputConnection connection = new FakeInputConnection("typed");
    KeyEventHandler handler = new_handler(connection);
    handler._typedword._enabled = true;

    handler.refresh_typing_config(false, false, false);
    handler._typedword.set_current_word("disabled");
    handler.send_text(" ");
    assertFalse(dictionary.contains("disabled"));

    handler.refresh_typing_config(false, false, true);
    handler._typedword.set_current_word("unknown");
    handler.send_text(" ");
    assertTrue(dictionary.contains("unknown"));

    connection = new FakeInputConnection("typed");
    handler = new_handler(connection);
    handler._typedword._enabled = true;
    handler._typedword.set_current_word("typed");
    handler.refresh_typing_config(false, false, true);
    handler._space_bar_auto_complete = true;
    handler._suggestions.suggestions[0] = "corrected";
    handler._suggestions.count = 1;
    handler.handle_space_bar();
    handler._last_action = KeyEventHandler.LastAction.SUGGESTION_ENTERED;
    handler.handle_backspace();
    handler.send_text(".");
    assertTrue(dictionary.contains("typed"));
  }

  @Test
  public void removed_personal_candidate_is_not_used_by_space_autocomplete()
  {
    FakeInputConnection connection = new FakeInputConnection();
    KeyEventHandler handler = new_handler(connection);
    handler._suggestions.suggestions[0] = "Removed";
    handler._suggestions.personal_suggestions[0] = true;
    handler._suggestions.count = 1;
    assertTrue(handler._suggestions.remove_personal_candidate("Removed"));
    handler._space_bar_auto_complete = true;

    handler.handle_space_bar();

    assertEquals(" ", connection.text());
  }

  KeyEventHandler new_handler(FakeInputConnection connection)
  {
    Receiver receiver = new Receiver(connection.connection);
    return new KeyEventHandler(receiver, new juloo.keyboard2.suggestions.Suggestions(receiver, null));
  }

  UserDictionary dictionary() throws Exception
  {
    File directory = java.nio.file.Files.createTempDirectory("key-handler").toFile();
    java.lang.reflect.Constructor<UserDictionary> constructor =
      UserDictionary.class.getDeclaredConstructor(File.class);
    constructor.setAccessible(true);
    return constructor.newInstance(new File(directory, "user_words.txt"));
  }

  void set_user_dictionary_instance(UserDictionary dictionary) throws Exception
  {
    java.lang.reflect.Field field = UserDictionary.class.getDeclaredField("_instance");
    field.setAccessible(true);
    field.set(null, dictionary);
  }

  static class Receiver implements KeyEventHandler.IReceiver
  {
    final InputConnection connection;
    int shift_changes = 0;
    boolean shift_latch_cleared = false;

    Receiver(InputConnection connection) { this.connection = connection; }
    public void handle_event_key(KeyValue.Event event) {}
    public void set_shift_state(boolean state, boolean lock) { shift_changes++; }
    public void clear_shift_latch() { shift_latch_cleared = true; }
    public void set_compose_pending(boolean pending) {}
    public void selection_state_changed(boolean selection) {}
    public InputConnection getCurrentInputConnection() { return connection; }
    public Handler getHandler() { return null; }
    public void set_suggestions(juloo.keyboard2.suggestions.Suggestions suggestions) {}
  }

  static class FakeInputConnection implements InvocationHandler
  {
    StringBuilder text;
    int cursor;
    int after_cursor_request = 0;

    FakeInputConnection()
    {
      this("");
    }

    FakeInputConnection(String text)
    {
      this.text = new StringBuilder(text);
      cursor = text.length();
      connection = (InputConnection)Proxy.newProxyInstance(
          InputConnection.class.getClassLoader(), new Class[] { InputConnection.class }, this);
    }

    final InputConnection connection;

    public Object invoke(Object proxy, Method method, Object[] args)
    {
      if (method.getName().equals("commitText"))
      {
        String inserted = args[0].toString();
        text.insert(cursor, inserted);
        cursor += inserted.length();
      }
      if (method.getName().equals("getTextAfterCursor"))
      {
        after_cursor_request = ((Integer)args[0]).intValue();
        return text.substring(cursor, Math.min(text.length(), cursor + after_cursor_request));
      }
      if (method.getName().equals("getTextBeforeCursor"))
      {
        int count = ((Integer)args[0]).intValue();
        return text.substring(Math.max(0, cursor - count), cursor);
      }
      if (method.getName().equals("deleteSurroundingText"))
      {
        int before = ((Integer)args[0]).intValue();
        int after = ((Integer)args[1]).intValue();
        text.delete(cursor - before, cursor + after);
        cursor -= before;
      }
      Class<?> type = method.getReturnType();
      if (type == Boolean.TYPE) return true;
      if (type == Integer.TYPE) return 0;
      return null;
    }

    String text() { return text.toString(); }
  }
}
