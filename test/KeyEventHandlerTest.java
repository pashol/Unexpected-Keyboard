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

    assertEquals("word", connection.committed);
  }

  @Test
  public void case_cycle_leaves_word_unchanged_before_a_following_letter()
  {
    FakeInputConnection connection = new FakeInputConnection();
    connection.after = "world";
    KeyEventHandler handler = new_handler(connection);
    handler._typedword._enabled = true;
    handler._typedword.set_current_word("hello");

    handler.cycle_typed_word_case();

    assertEquals("", connection.committed);
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

    assertEquals(". ! ", connection.committed);
    assertEquals(1, connection.deleted_before);
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

  static class Receiver implements KeyEventHandler.IReceiver
  {
    final InputConnection connection;

    Receiver(InputConnection connection) { this.connection = connection; }
    public void handle_event_key(KeyValue.Event event) {}
    public void set_shift_state(boolean state, boolean lock) {}
    public void set_compose_pending(boolean pending) {}
    public void selection_state_changed(boolean selection) {}
    public InputConnection getCurrentInputConnection() { return connection; }
    public Handler getHandler() { return null; }
    public void set_suggestions(juloo.keyboard2.suggestions.Suggestions suggestions) {}
  }

  static class FakeInputConnection implements InvocationHandler
  {
    String committed = "";
    String after = "";
    int deleted_before = 0;

    FakeInputConnection()
    {
      connection = (InputConnection)Proxy.newProxyInstance(
          InputConnection.class.getClassLoader(), new Class[] { InputConnection.class }, this);
    }

    final InputConnection connection;

    public Object invoke(Object proxy, Method method, Object[] args)
    {
      if (method.getName().equals("commitText"))
        committed += args[0].toString();
      if (method.getName().equals("getTextAfterCursor"))
        return after;
      if (method.getName().equals("deleteSurroundingText"))
        deleted_before += ((Integer)args[0]).intValue();
      Class<?> type = method.getReturnType();
      if (type == Boolean.TYPE) return true;
      if (type == Integer.TYPE) return 0;
      return null;
    }
  }
}
