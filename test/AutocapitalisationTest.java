package juloo.keyboard2;

import android.view.KeyEvent;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutocapitalisationTest
{
  @Test
  public void shifted_enter_refreshes_automatic_capitalization()
  {
    assertTrue(Autocapitalisation.should_refresh_caps_mode_after_event(
        KeyEvent.KEYCODE_ENTER, KeyEvent.META_SHIFT_ON));
  }
}
