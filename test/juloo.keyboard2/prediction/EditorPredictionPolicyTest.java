package juloo.keyboard2.prediction;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.Test;
import static org.junit.Assert.*;

public class EditorPredictionPolicyTest
{
  private static final int NO_PERSONALIZED_LEARNING = 0x1000000;

  @Test
  public void applies_prediction_privacy_and_capability_matrix()
  {
    Case[] cases = {
        allow("ordinary text", InputType.TYPE_CLASS_TEXT, 0, true, true, true),
        deny("text password", InputType.TYPE_CLASS_TEXT |
            InputType.TYPE_TEXT_VARIATION_PASSWORD, 0),
        deny("web password", InputType.TYPE_CLASS_TEXT |
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, 0),
        deny("visible password", InputType.TYPE_CLASS_TEXT |
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, 0),
        deny("number password", InputType.TYPE_CLASS_NUMBER |
            InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0),
        allow("no personalized learning", InputType.TYPE_CLASS_TEXT,
            NO_PERSONALIZED_LEARNING, true, true, false),
        allow("URI", InputType.TYPE_CLASS_TEXT |
            InputType.TYPE_TEXT_VARIATION_URI, 0, true, false, false),
        allow("email", InputType.TYPE_CLASS_TEXT |
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 0, true, false, false),
        allow("web email", InputType.TYPE_CLASS_TEXT |
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, 0, true, false, false),
        deny("no suggestions", InputType.TYPE_CLASS_TEXT |
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 0),
        deny("number", InputType.TYPE_CLASS_NUMBER, 0),
        deny("phone", InputType.TYPE_CLASS_PHONE, 0),
        deny("datetime", InputType.TYPE_CLASS_DATETIME, 0),
        deny("password with no personalized learning", InputType.TYPE_CLASS_TEXT |
            InputType.TYPE_TEXT_VARIATION_PASSWORD, NO_PERSONALIZED_LEARNING),
        deny("password with no suggestions", InputType.TYPE_CLASS_TEXT |
            InputType.TYPE_TEXT_VARIATION_PASSWORD |
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 0)
    };

    for (Case testCase : cases)
    {
      EditorPredictionPolicy policy = EditorPredictionPolicy.from(
          testCase.inputType, testCase.imeOptions, null);

      assertEquals(testCase.name + " prediction",
          testCase.allowPrediction, policy.allowsPrediction());
      assertEquals(testCase.name + " correction",
          testCase.allowCorrection, policy.allowsCorrection());
      assertEquals(testCase.name + " learning",
          testCase.allowLearning, policy.allowsLearning());
    }
  }

  @Test
  public void retains_normalized_editor_flags_and_private_options()
  {
    int inputType = InputType.TYPE_CLASS_TEXT |
        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS |
        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES |
        InputType.TYPE_TEXT_FLAG_MULTI_LINE;
    int imeOptions = EditorInfo.IME_ACTION_SEND | NO_PERSONALIZED_LEARNING;

    EditorPredictionPolicy policy = EditorPredictionPolicy.from(
        inputType, imeOptions, "com.example.future-option");

    assertEquals(inputType, policy.getInputType());
    assertEquals(InputType.TYPE_CLASS_TEXT, policy.getInputClass());
    assertEquals(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        policy.getInputVariation());
    assertEquals(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES |
        InputType.TYPE_TEXT_FLAG_MULTI_LINE, policy.getInputFlags());
    assertEquals(imeOptions, policy.getImeOptions());
    assertEquals("com.example.future-option", policy.getPrivateImeOptions());
    assertNull(EditorPredictionPolicy.from(inputType, 0, null).getPrivateImeOptions());
  }

  @Test
  public void is_immutable() throws Exception
  {
    assertTrue(Modifier.isFinal(EditorPredictionPolicy.class.getModifiers()));
    for (Field field : EditorPredictionPolicy.class.getDeclaredFields())
    {
      assertTrue(field.getName() + " must be private",
          Modifier.isPrivate(field.getModifiers()));
      assertTrue(field.getName() + " must be final",
          Modifier.isFinal(field.getModifiers()));
    }
  }

  private static Case allow(String name, int inputType, int imeOptions,
      boolean prediction, boolean correction, boolean learning)
  {
    return new Case(name, inputType, imeOptions, prediction, correction, learning);
  }

  private static Case deny(String name, int inputType, int imeOptions)
  {
    return new Case(name, inputType, imeOptions, false, false, false);
  }

  private static final class Case
  {
    final String name;
    final int inputType;
    final int imeOptions;
    final boolean allowPrediction;
    final boolean allowCorrection;
    final boolean allowLearning;

    Case(String name, int inputType, int imeOptions, boolean allowPrediction,
        boolean allowCorrection, boolean allowLearning)
    {
      this.name = name;
      this.inputType = inputType;
      this.imeOptions = imeOptions;
      this.allowPrediction = allowPrediction;
      this.allowCorrection = allowCorrection;
      this.allowLearning = allowLearning;
    }
  }
}
