package juloo.keyboard2;

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
}
