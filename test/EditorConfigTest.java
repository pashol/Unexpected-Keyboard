package juloo.keyboard2;

import android.text.InputType;
import org.junit.Test;
import static org.junit.Assert.*;

public class EditorConfigTest
{
  @Test
  public void punctuation_auto_spacing_is_disabled_for_ineligible_editors()
  {
    assertTrue(EditorConfig.no_auto_space_after_punct_for_input_type(
        InputType.TYPE_CLASS_NUMBER));
    assertTrue(EditorConfig.no_auto_space_after_punct_for_input_type(
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
    assertTrue(EditorConfig.no_auto_space_after_punct_for_input_type(
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
    assertTrue(EditorConfig.no_auto_space_after_punct_for_input_type(
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD));
    assertTrue(EditorConfig.no_auto_space_after_punct_for_input_type(
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI));
    assertTrue(EditorConfig.no_auto_space_after_punct_for_input_type(
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS));
    assertTrue(EditorConfig.no_auto_space_after_punct_for_input_type(
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS));
  }

  @Test
  public void punctuation_auto_spacing_is_enabled_for_plain_text()
  {
    assertFalse(EditorConfig.no_auto_space_after_punct_for_input_type(
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL));
  }
}
