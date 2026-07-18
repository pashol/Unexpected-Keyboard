package juloo.keyboard2;

import android.os.Handler;
import android.view.inputmethod.InputConnection;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.junit.Test;
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
      Class<?> type = method.getReturnType();
      if (type == Boolean.TYPE) return true;
      if (type == Integer.TYPE) return 0;
      return null;
    }
  }
}
