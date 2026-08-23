package juloo.keyboard2.prediction;

import android.text.InputType;

public final class EditorPredictionPolicy
{
  private EditorPredictionPolicy() {}

  public static boolean allow_next_word(int inputType)
  {
    if ((inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT
        || (inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        || (inputType & (InputType.TYPE_TEXT_VARIATION_PASSWORD
            & InputType.TYPE_MASK_VARIATION)) != 0)
      return false;
    switch (inputType & InputType.TYPE_MASK_VARIATION)
    {
      case InputType.TYPE_TEXT_VARIATION_PASSWORD:
      case InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:
      case InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD:
      case InputType.TYPE_TEXT_VARIATION_URI:
      case InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS:
      case InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS:
        return false;
      default:
        return true;
    }
  }
}
