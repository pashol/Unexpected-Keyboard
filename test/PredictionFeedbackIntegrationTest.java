package juloo.keyboard2;

import android.os.Handler;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import juloo.keyboard2.prediction.CandidateType;
import juloo.keyboard2.prediction.ComposingContext;
import juloo.keyboard2.prediction.EditorPredictionPolicy;
import juloo.keyboard2.prediction.FeedbackType;
import juloo.keyboard2.prediction.PredictionCandidate;
import juloo.keyboard2.prediction.PredictionEngine;
import juloo.keyboard2.prediction.PredictionFeedback;
import juloo.keyboard2.prediction.PredictionRequest;
import juloo.keyboard2.suggestions.Suggestions;
import org.junit.Test;
import static org.junit.Assert.*;

public class PredictionFeedbackIntegrationTest
{
  @Test public void accepted_tap_records_the_current_structured_candidate()
  {
    Fixture fixture = fixture(CandidateType.COMPLETION);
    long generation = fixture.suggestions.current_generation();

    fixture.handler.suggestion_entered(0, generation);

    assertEquals(1, fixture.engine.feedback.size());
    assertFeedback(fixture.engine.feedback.get(0), FeedbackType.ACCEPTED,
        fixture.candidate, fixture.candidate.getText());
    assertEquals(generation, fixture.engine.feedback.get(0).getGeneration());
  }

  @Test public void delimiter_commits_the_typed_word()
  {
    Fixture fixture = fixture(CandidateType.COMPLETION);
    fixture.handler._typedword._enabled = true;
    fixture.handler._typedword.set_current_word("typed");

    fixture.handler.send_text(" ");

    assertEquals(1, fixture.engine.feedback.size());
    assertFeedback(fixture.engine.feedback.get(0), FeedbackType.COMMITTED,
        null, "typed");
  }

  @Test public void delimiter_rejects_a_visible_correction()
  {
    Fixture fixture = fixture(CandidateType.CORRECTION);
    fixture.handler._typedword._enabled = true;
    fixture.handler._typedword.set_current_word("typed");

    fixture.handler.send_text(" ");

    assertEquals(2, fixture.engine.feedback.size());
    assertFeedback(fixture.engine.feedback.get(0), FeedbackType.REJECTED,
        fixture.candidate, "typed");
    assertFeedback(fixture.engine.feedback.get(1), FeedbackType.COMMITTED,
        null, "typed");
  }

  @Test public void immediate_backspace_reverts_an_accepted_candidate()
  {
    Fixture fixture = fixture(CandidateType.COMPLETION);
    long generation = fixture.suggestions.current_generation();
    fixture.handler.suggestion_entered(0, generation);

    fixture.handler.key_up(KeyValue.getKeyByName("backspace"),
        Pointers.Modifiers.EMPTY);

    assertEquals(2, fixture.engine.feedback.size());
    assertFeedback(fixture.engine.feedback.get(1), FeedbackType.REVERTED,
        fixture.candidate, "typed");
    assertEquals(generation, fixture.engine.feedback.get(1).getGeneration());
  }

  @Test public void post_commit_selection_callback_preserves_immediate_revert()
  {
    Fixture fixture = fixture(CandidateType.COMPLETION);
    long generation = fixture.suggestions.current_generation();
    fixture.handler.suggestion_entered(0, generation);
    fixture.handler._last_action = KeyEventHandler.LastAction.SUGGESTION_ENTERED;
    fixture.handler._meta_state = KeyEvent.META_CTRL_ON;

    fixture.handler.selection_updated(5, 10, 10);
    fixture.handler._typedword._enabled = false;
    fixture.handler.handle_backspace();

    assertEquals("typed", fixture.connection.text());
    assertEquals(2, fixture.engine.feedback.size());
    assertFeedback(fixture.engine.feedback.get(1), FeedbackType.REVERTED,
        fixture.candidate, "typed");
  }

  @Test public void immediate_backspace_routes_revert_after_contextual_generations()
  {
    Fixture fixture = fixture(CandidateType.COMPLETION);
    long generation = fixture.suggestions.current_generation();
    fixture.handler.suggestion_entered(0, generation);
    fixture.handler._last_action = KeyEventHandler.LastAction.SUGGESTION_ENTERED;
    fixture.suggestions.currently_typed_word(new ComposingContext(
        "candidate", 9, Collections.singletonList("before"), false, true));
    fixture.suggestions.currently_typed_word(new ComposingContext(
        "candidate", 9, Collections.singletonList("context"), false, true));

    fixture.handler.handle_backspace();

    assertEquals(2, fixture.engine.feedback.size());
    assertFeedback(fixture.engine.feedback.get(1), FeedbackType.REVERTED,
        fixture.candidate, "typed");
    assertEquals(generation, fixture.engine.feedback.get(1).getGeneration());
  }

  @Test public void cursor_movement_cancels_a_pending_revert()
  {
    Fixture fixture = fixture(CandidateType.COMPLETION);
    fixture.handler.suggestion_entered(0, fixture.suggestions.current_generation());
    fixture.handler._last_action = KeyEventHandler.LastAction.SUGGESTION_ENTERED;
    fixture.connection.cursor--;
    fixture.handler.selection_updated(5, 4, 4);

    assertEquals(1, fixture.engine.feedback.size());
    assertEquals(FeedbackType.ACCEPTED, fixture.engine.feedback.get(0).getType());
    assertNull(fixture.handler.last_replaced_word);
    assertNull(fixture.handler.last_replacement_candidate);
  }

  @Test public void stale_candidate_tap_is_rejected()
  {
    Fixture fixture = fixture(CandidateType.COMPLETION);
    long staleGeneration = fixture.suggestions.current_generation();
    fixture.suggestions.currently_typed_word(new ComposingContext(
        "new", 3, Collections.emptyList(), false, false));

    fixture.handler.suggestion_entered(0, staleGeneration);

    assertTrue(fixture.engine.feedback.isEmpty());
    assertEquals("typed", fixture.connection.text());
  }

  @Test public void privacy_suppression_does_not_emit_feedback()
  {
    Fixture fixture = fixture(CandidateType.COMPLETION);
    fixture.controller.predict(new PredictionRequest("", 0, Collections.emptyList(),
        false, "en", 3, fixture.suggestions.current_generation() + 1,
        EditorPredictionPolicy.from(InputType.TYPE_TEXT_VARIATION_PASSWORD, 0, null)));

    fixture.handler.suggestion_entered(0, fixture.suggestions.current_generation());

    assertTrue(fixture.engine.feedback.isEmpty());
  }

  private static void assertFeedback(PredictionFeedback feedback, FeedbackType type,
      PredictionCandidate candidate, String committedText)
  {
    assertEquals(type, feedback.getType());
    assertSame(candidate, feedback.getCandidate());
    assertEquals(committedText, feedback.getCommittedText());
  }

  private static Fixture fixture(CandidateType type)
  {
    PredictionCandidate candidate = new PredictionCandidate("candidate", "en", type,
        "test", 0, 0, 0, 0, 0);
    CapturingEngine engine = new CapturingEngine(candidate);
    CapturingConnection connection = new CapturingConnection("typed");
    Receiver receiver = new Receiver(connection.connection);
    juloo.keyboard2.prediction.PredictionEngineController controller =
        new juloo.keyboard2.prediction.PredictionEngineController(engine, null, false);
    Suggestions suggestions = new Suggestions(receiver, null, controller);
    set_suggestions_enabled(suggestions);
    suggestions.currently_typed_word(new ComposingContext(
        "typed", 5, Collections.emptyList(), false, false));
    KeyEventHandler handler = new KeyEventHandler(receiver, suggestions);
    handler._typedword._enabled = true;
    handler._typedword._ic = connection.connection;
    handler._typedword._cursor = 5;
    handler._typedword.set_current_word("typed");
    return new Fixture(handler, suggestions, controller, engine, candidate, connection);
  }

  private static void set_suggestions_enabled(Suggestions suggestions)
  {
    try
    {
      java.lang.reflect.Field field = Suggestions.class.getDeclaredField("_enabled");
      field.setAccessible(true);
      field.setBoolean(suggestions, true);
    }
    catch (ReflectiveOperationException e)
    {
      throw new AssertionError(e);
    }
  }

  private static final class Fixture
  {
    final KeyEventHandler handler;
    final Suggestions suggestions;
    final juloo.keyboard2.prediction.PredictionEngineController controller;
    final CapturingEngine engine;
    final PredictionCandidate candidate;
    final CapturingConnection connection;

    Fixture(KeyEventHandler handler, Suggestions suggestions,
        juloo.keyboard2.prediction.PredictionEngineController controller,
        CapturingEngine engine, PredictionCandidate candidate, CapturingConnection connection)
    {
      this.handler = handler;
      this.suggestions = suggestions;
      this.controller = controller;
      this.engine = engine;
      this.candidate = candidate;
      this.connection = connection;
    }
  }

  private static final class CapturingEngine implements PredictionEngine
  {
    final PredictionCandidate candidate;
    final List<PredictionFeedback> feedback = new ArrayList<>();

    CapturingEngine(PredictionCandidate candidate) { this.candidate = candidate; }
    public List<PredictionCandidate> predict(PredictionRequest request)
    {
      return Collections.singletonList(candidate);
    }
    public void recordFeedback(PredictionFeedback value) { feedback.add(value); }
    public void resetSession() {}
    public void close() {}
  }

  private static final class Receiver implements KeyEventHandler.IReceiver
  {
    final InputConnection connection;
    Receiver(InputConnection connection) { this.connection = connection; }
    public void handle_event_key(KeyValue.Event event) {}
    public void set_shift_state(boolean state, boolean lock) {}
    public void clear_shift_latch() {}
    public void set_compose_pending(boolean pending) {}
    public void selection_state_changed(boolean selection) {}
    public InputConnection getCurrentInputConnection() { return connection; }
    public Handler getHandler() { return null; }
    public void set_suggestions(Suggestions suggestions) {}
  }

  private static final class CapturingConnection implements InvocationHandler
  {
    final StringBuilder value;
    int cursor;
    final InputConnection connection;

    CapturingConnection(String text)
    {
      value = new StringBuilder(text);
      cursor = text.length();
      connection = (InputConnection)Proxy.newProxyInstance(
          InputConnection.class.getClassLoader(), new Class[] { InputConnection.class }, this);
    }

    public Object invoke(Object proxy, Method method, Object[] args)
    {
      if (method.getName().equals("commitText"))
      {
        String text = args[0].toString();
        value.insert(cursor, text);
        cursor += text.length();
      }
      else if (method.getName().equals("deleteSurroundingText"))
      {
        int before = (Integer)args[0];
        int after = (Integer)args[1];
        value.delete(cursor - before, cursor + after);
        cursor -= before;
      }
      else if (method.getName().equals("getTextAfterCursor"))
        return value.substring(cursor, Math.min(value.length(), cursor + (Integer)args[0]));
      else if (method.getName().equals("getTextBeforeCursor"))
        return value.substring(Math.max(0, cursor - (Integer)args[0]), cursor);
      if (method.getReturnType() == Boolean.TYPE) return true;
      if (method.getReturnType() == Integer.TYPE) return 0;
      return null;
    }

    String text() { return value.toString(); }
  }
}
