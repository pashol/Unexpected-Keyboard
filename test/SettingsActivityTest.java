package juloo.keyboard2;

import java.util.concurrent.Executor;
import org.junit.Test;
import static org.junit.Assert.*;

public class SettingsActivityTest
{
  @Test
  public void reports_when_import_adds_no_words()
  {
    assertEquals(R.string.user_dictionary_import_no_new_words,
        SettingsActivity.import_result_message(0));
  }

  @Test
  public void delegates_dictionary_work_to_supplied_executor()
  {
    final Runnable[] delegated = new Runnable[1];
    Executor executor = command -> delegated[0] = command;
    final boolean[] ran = { false };

    SettingsActivity.run_in_background(executor, () -> ran[0] = true);

    assertNotNull(delegated[0]);
    assertFalse(ran[0]);
    delegated[0].run();
    assertTrue(ran[0]);
  }
}
