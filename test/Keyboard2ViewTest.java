package juloo.keyboard2;

import org.junit.Test;
import juloo.keyboard2.suggestions.Suggestions;
import static org.junit.Assert.*;

public class Keyboard2ViewTest
{
  @Test
  public void reset_notifies_handler_of_empty_unshifted_state()
  {
    ResetHandler handler = new ResetHandler();

    Keyboard2View.notify_reset(handler);

    assertEquals(Pointers.Modifiers.EMPTY, handler.mods);
    assertEquals(Pointers.ShiftState.OFF, handler.shift_state);
  }

  @Test
  public void reset_without_handler_is_safe()
  {
    Keyboard2View.notify_reset(null);
  }

  static class ResetHandler implements Config.IKeyEventHandler
  {
    Pointers.Modifiers mods;
    Pointers.ShiftState shift_state;

    public void key_down(KeyValue value, boolean is_swipe) {}
    public void key_up(KeyValue value, Pointers.Modifiers modifiers) {}
    public void mods_changed(Pointers.Modifiers modifiers,
        Pointers.ShiftState state)
    {
      mods = modifiers;
      shift_state = state;
    }
    public void suggestion_entered(String text) {}
    public void candidate_entered(String text, Suggestions.CandidateType type) {}
    public void personal_candidate_removed(String text) {}
  }
}
