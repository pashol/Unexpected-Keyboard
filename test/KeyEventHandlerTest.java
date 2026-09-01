package juloo.keyboard2;

import android.os.Handler;
import android.view.inputmethod.InputConnection;
import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.junit.Test;
import juloo.keyboard2.suggestions.Suggestions;
import juloo.keyboard2.suggestions.UserDictionary;
import static org.junit.Assert.*;

public class KeyEventHandlerTest
{
  @Test
  public void candidate_case_for_shift_maps_shift_states()
  {
    assertEquals(Suggestions.CandidateCase.DEFAULT,
        KeyEventHandler.candidate_case_for_shift(Pointers.ShiftState.OFF));
    assertEquals(Suggestions.CandidateCase.TITLE,
        KeyEventHandler.candidate_case_for_shift(Pointers.ShiftState.SHIFTED));
    assertEquals(Suggestions.CandidateCase.UPPER,
        KeyEventHandler.candidate_case_for_shift(Pointers.ShiftState.LOCKED));
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
  public void entering_suggestion_appends_a_space()
  {
    FakeInputConnection connection = new FakeInputConnection();
    KeyEventHandler handler = new KeyEventHandler(new Receiver(connection.connection), null);

    handler.suggestion_entered("word");

    assertEquals("word ", connection.text());
  }

  @Test
  public void tapped_completion_replaces_typed_word_and_appends_space()
  {
    FakeInputConnection connection = new FakeInputConnection("Informa");
    KeyEventHandler handler = new_handler(connection);
    handler._typedword._enabled = true;
    handler._typedword.set_current_word("Informa");

    handler.suggestion_entered("Informatik");

    assertEquals("Informatik ", connection.text());
    assertNull(handler.last_replaced_word);
  }

  @Test
  public void backspace_after_tapped_completion_removes_only_its_space()
  {
    FakeInputConnection connection = new FakeInputConnection("Informa");
    KeyEventHandler handler = new_handler(connection);
    handler._typedword._enabled = true;
    handler._typedword.set_current_word("Informa");

    handler.suggestion_entered("Informatik");
    handler.handle_backspace();

    assertEquals("Informatik", connection.text());
  }

  @Test
  public void backspace_after_space_bar_completion_restores_typed_word()
  {
    FakeInputConnection connection = new FakeInputConnection("Informa");
    KeyEventHandler handler = new_handler(connection);
    handler._typedword._enabled = true;
    handler._typedword.set_current_word("Informa");
    handler._space_bar_auto_complete = true;
    handler._suggestions.suggestions[0] = "Informatik";
    handler._suggestions.count = 1;

    handler.handle_space_bar();
    handler._last_action = KeyEventHandler.LastAction.SUGGESTION_ENTERED;
    handler.handle_backspace();

    assertEquals("Informa", connection.text());
  }

  @Test
  public void space_does_not_arm_autocomplete_undo_for_unchanged_candidate()
  {
    FakeInputConnection connection = new FakeInputConnection("typed");
    KeyEventHandler handler = new_handler(connection);
    handler._typedword._enabled = true;
    handler._typedword.set_current_word("typed");
    handler._space_bar_auto_complete = true;
    handler._suggestions.suggestions[0] = "typed";
    handler._suggestions.count = 1;

    handler.handle_space_bar();

    assertEquals("typed ", connection.text());
    assertNull(handler.last_replaced_word);
  }

  @Test
  public void space_autocomplete_accepts_case_only_completion()
  {
    FakeInputConnection connection = new FakeInputConnection("typed");
    KeyEventHandler handler = new_handler(connection);
    handler._typedword._enabled = true;
    handler._typedword.set_current_word("typed");
    handler._space_bar_auto_complete = true;
    handler._suggestions.suggestions[0] = "Typed";
    handler._suggestions.count = 1;

    handler.handle_space_bar();

    assertEquals("Typed ", connection.text());
    assertEquals("typed", handler.last_replaced_word);
    assertEquals(6, handler.last_replacement_word_len);
  }

  @Test
  public void next_word_candidate_tap_batches_an_append_without_deleting_adjacent_text()
  {
    FakeInputConnection connection = new FakeInputConnection("hello !");
    connection.cursor = 6;
    KeyEventHandler handler = new_handler(connection);

    handler.candidate_entered("world", Suggestions.CandidateType.NEXT_WORD);

    assertEquals("hello world !", connection.text());
    assertEquals(0, connection.delete_calls);
    assertEquals(1, connection.begin_batch_calls);
    assertEquals(1, connection.end_batch_calls);
  }

  @Test
  public void backspace_after_tapped_next_word_removes_only_its_space()
  {
    FakeInputConnection connection = new FakeInputConnection("hello ");
    KeyEventHandler handler = new_handler(connection);

    handler.next_word_entered("world");
    handler.handle_backspace();

    assertEquals("hello world", connection.text());
  }

  @Test
  public void candidate_entry_replaces_completion_and_appends_space()
  {
    FakeInputConnection connection = new FakeInputConnection("hel");
    KeyEventHandler handler = new_handler(connection);
    handler._typedword._enabled = true;
    handler._typedword.set_current_word("hel");

    handler.candidate_entered("hello", Suggestions.CandidateType.COMPLETION);

    assertEquals("hello ", connection.text());
    assertEquals(1, connection.delete_calls);
  }

  @Test
  public void tapped_candidate_preserves_displayed_casing()
  {
    FakeInputConnection connection = new FakeInputConnection("informa");
    KeyEventHandler handler = new_handler(connection);
    handler._typedword._enabled = true;
    handler._typedword.set_current_word("informa");

    handler.candidate_entered("InFoRmAtIk", Suggestions.CandidateType.COMPLETION);

    assertEquals("InFoRmAtIk ", connection.text());
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
  public void one_shot_shift_leaves_editor_word_unchanged()
  {
    FakeInputConnection connection = new FakeInputConnection("hello");
    KeyEventHandler handler = new_handler(connection);
    handler._typedword._enabled = true;
    handler._typedword.set_current_word("hello");

    handler.key_down(KeyValue.SHIFT, false);
    handler.mods_changed(Pointers.Modifiers.EMPTY.with_extra_mod(KeyValue.SHIFT),
        Pointers.ShiftState.SHIFTED);

    assertEquals("hello", connection.text());
    assertEquals(0, connection.after_cursor_request);
    assertEquals(0, connection.delete_calls);
  }

  @Test
  public void shift_at_internal_cursor_leaves_editor_word_unchanged()
  {
    FakeInputConnection connection = new FakeInputConnection("hello world");
    connection.cursor = 3;
    KeyEventHandler handler = new_handler(connection);
    handler._typedword._enabled = true;
    handler._typedword.set_current_word("hello");

    handler.key_down(KeyValue.SHIFT, false);
    handler.mods_changed(Pointers.Modifiers.EMPTY.with_extra_mod(KeyValue.SHIFT),
        Pointers.ShiftState.SHIFTED);

    assertEquals("hello world", connection.text());
    assertEquals(3, connection.cursor);
    assertEquals(0, connection.delete_calls);
  }

  @Test
  public void locked_shift_leaves_editor_word_unchanged()
  {
    FakeInputConnection connection = new FakeInputConnection("hello");
    KeyEventHandler handler = new_handler(connection);
    handler._typedword._enabled = true;
    handler._typedword.set_current_word("hello");

    handler.key_down(KeyValue.SHIFT, false);
    handler.mods_changed(Pointers.Modifiers.EMPTY.with_extra_mod(KeyValue.SHIFT),
        Pointers.ShiftState.LOCKED);

    assertEquals("hello", connection.text());
    assertEquals(0, connection.after_cursor_request);
    assertEquals(0, connection.delete_calls);
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

  @Test
  public void next_word_candidate_is_not_used_by_space_autocomplete()
  {
    FakeInputConnection connection = new FakeInputConnection();
    KeyEventHandler handler = new_handler(connection);
    handler._suggestions.suggestions[0] = "world";
    handler._suggestions.types[0] = Suggestions.CandidateType.NEXT_WORD;
    handler._suggestions.count = 1;
    handler._space_bar_auto_complete = true;

    handler.handle_space_bar();

    assertEquals(" ", connection.text());
  }

  @Test
  public void startup_retains_suggestions_for_initial_typed_word() throws Exception
  {
    UserDictionary dictionary = dictionary();
    dictionary.add("initial");
    set_user_dictionary_instance(dictionary);
    FakeInputConnection connection = new FakeInputConnection("initial");
    Receiver receiver = new Receiver(connection.connection);
    Config config = config_with_initial_text("initial", "");
    config.user_dictionary_enabled = true;
    config.editor_config.should_show_candidates_view = true;
    KeyEventHandler handler = new KeyEventHandler(receiver,
        new juloo.keyboard2.suggestions.Suggestions(receiver, config));

    handler.started(config);

    assertEquals(1, receiver.suggestion_updates);
    assertEquals(1, receiver.last_suggestions.count);
    assertEquals("initial", receiver.last_suggestions.suggestions[0]);
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

  Config config_with_initial_text(String before, String after) throws Exception
  {
    Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
    java.lang.reflect.Field field = unsafeClass.getDeclaredField("theUnsafe");
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
    int suggestion_updates = 0;
    juloo.keyboard2.suggestions.Suggestions last_suggestions;

    Receiver(InputConnection connection) { this.connection = connection; }
    public void handle_event_key(KeyValue.Event event) {}
    public void set_shift_state(boolean state, boolean lock) { shift_changes++; }
    public void set_compose_pending(boolean pending) {}
    public void selection_state_changed(boolean selection) {}
    public InputConnection getCurrentInputConnection() { return connection; }
    public Handler getHandler() { return null; }
    public void set_suggestions(juloo.keyboard2.suggestions.Suggestions suggestions)
    {
      suggestion_updates++;
      last_suggestions = suggestions;
    }
  }

  static class FakeInputConnection implements InvocationHandler
  {
    StringBuilder text;
    int cursor;
    int after_cursor_request = 0;
    int delete_calls = 0;
    int begin_batch_calls = 0;
    int end_batch_calls = 0;
    int key_event_count = 0;

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
        delete_calls++;
        int before = ((Integer)args[0]).intValue();
        int after = ((Integer)args[1]).intValue();
        text.delete(cursor - before, cursor + after);
        cursor -= before;
      }
      if (method.getName().equals("sendKeyEvent"))
      {
        key_event_count++;
        // The local Android KeyEvent stubs do not expose event properties.
        // Key events are sent in down/up pairs, so the second event is the up event.
        if (key_event_count % 2 == 0 && cursor > 0
            && text.charAt(cursor - 1) == ' ')
        {
          text.deleteCharAt(cursor - 1);
          cursor--;
        }
      }
      if (method.getName().equals("beginBatchEdit"))
        begin_batch_calls++;
      if (method.getName().equals("endBatchEdit"))
        end_batch_calls++;
      Class<?> type = method.getReturnType();
      if (type == Boolean.TYPE) return true;
      if (type == Integer.TYPE) return 0;
      return null;
    }

    String text() { return text.toString(); }
  }
}
