package juloo.keyboard2.prediction;

import android.text.InputType;
import org.junit.Test;
import static org.junit.Assert.*;

public class EditorPredictionPolicyTest
{
  @Test
  public void next_word_is_denied_for_password_fields()
  {
    assertFalse(EditorPredictionPolicy.allow_next_word(
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
    assertFalse(EditorPredictionPolicy.allow_next_word(
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
    assertFalse(EditorPredictionPolicy.allow_next_word(
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD));
    assertFalse(EditorPredictionPolicy.allow_next_word(
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
        | InputType.TYPE_TEXT_VARIATION_PERSON_NAME));
    assertFalse(EditorPredictionPolicy.allow_next_word(
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
        | InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS));
  }

  @Test
  public void next_word_is_allowed_for_non_password_text_variations()
  {
    assertTrue(EditorPredictionPolicy.allow_next_word(
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT));
    assertTrue(EditorPredictionPolicy.allow_next_word(
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_FILTER));
    assertTrue(EditorPredictionPolicy.allow_next_word(
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PHONETIC));
    // PASSWORD | FILTER has FILTER's bit pattern, so it is a valid filter field here.
    assertEquals(InputType.TYPE_TEXT_VARIATION_FILTER,
        InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_VARIATION_FILTER);
  }

  @Test
  public void next_word_is_denied_for_uri_fields()
  {
    assertFalse(EditorPredictionPolicy.allow_next_word(
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI));
  }

  @Test
  public void next_word_is_denied_for_email_fields()
  {
    assertFalse(EditorPredictionPolicy.allow_next_word(
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS));
    assertFalse(EditorPredictionPolicy.allow_next_word(
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS));
  }

  @Test
  public void next_word_is_denied_when_suggestions_are_disabled()
  {
    assertFalse(EditorPredictionPolicy.allow_next_word(
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
  }

  @Test
  public void next_word_is_denied_for_non_text_fields()
  {
    assertFalse(EditorPredictionPolicy.allow_next_word(InputType.TYPE_CLASS_NUMBER));
  }

  @Test
  public void next_word_is_allowed_for_normal_text()
  {
    assertTrue(EditorPredictionPolicy.allow_next_word(
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL));
  }
}
