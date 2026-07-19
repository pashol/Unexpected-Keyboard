package juloo.keyboard2.prediction;

import android.text.InputType;

public final class EditorPredictionPolicy
{
  private static final int IME_FLAG_NO_PERSONALIZED_LEARNING = 0x1000000;

  private final int inputType;
  private final int inputClass;
  private final int inputVariation;
  private final int inputFlags;
  private final int imeOptions;
  private final String privateImeOptions;
  private final boolean allowPrediction;
  private final boolean allowCorrection;
  private final boolean allowLearning;

  private EditorPredictionPolicy(int inputType, int imeOptions,
      String privateImeOptions, boolean allowPrediction,
      boolean allowCorrection, boolean allowLearning)
  {
    this.inputType = inputType;
    inputClass = inputType & InputType.TYPE_MASK_CLASS;
    inputVariation = inputType & InputType.TYPE_MASK_VARIATION;
    inputFlags = inputType & InputType.TYPE_MASK_FLAGS;
    this.imeOptions = imeOptions;
    this.privateImeOptions = privateImeOptions;
    this.allowPrediction = allowPrediction;
    this.allowCorrection = allowCorrection;
    this.allowLearning = allowLearning;
  }

  public static EditorPredictionPolicy from(
      int inputType, int imeOptions, String privateImeOptions)
  {
    int inputClass = inputType & InputType.TYPE_MASK_CLASS;
    int variation = inputType & InputType.TYPE_MASK_VARIATION;
    boolean noSuggestions =
        (inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0;

    if (inputClass != InputType.TYPE_CLASS_TEXT || noSuggestions)
      return new EditorPredictionPolicy(
          inputType, imeOptions, privateImeOptions, false, false, false);

    // OR-ed password variations can collapse into named upper-range values.
    // Only explicitly supported, unambiguous variations may enable prediction.
    switch (variation)
    {
      case InputType.TYPE_TEXT_VARIATION_URI:
      case InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS:
        return new EditorPredictionPolicy(
            inputType, imeOptions, privateImeOptions, true, false, false);

      case InputType.TYPE_TEXT_VARIATION_NORMAL:
      case InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT:
      case InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE:
      case InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE:
      case InputType.TYPE_TEXT_VARIATION_PERSON_NAME:
      case InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS:
        boolean allowLearning =
            (imeOptions & IME_FLAG_NO_PERSONALIZED_LEARNING) == 0;
        return new EditorPredictionPolicy(inputType, imeOptions,
            privateImeOptions, true, true, allowLearning);

      case InputType.TYPE_TEXT_VARIATION_PASSWORD:
      case InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:
      case InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD:
      default:
        return new EditorPredictionPolicy(
            inputType, imeOptions, privateImeOptions, false, false, false);
    }
  }

  public int getInputType()
  {
    return inputType;
  }

  public int getInputClass()
  {
    return inputClass;
  }

  public int getInputVariation()
  {
    return inputVariation;
  }

  public int getInputFlags()
  {
    return inputFlags;
  }

  public int getImeOptions()
  {
    return imeOptions;
  }

  public String getPrivateImeOptions()
  {
    return privateImeOptions;
  }

  public boolean allowsPrediction()
  {
    return allowPrediction;
  }

  public boolean allowsCorrection()
  {
    return allowCorrection;
  }

  public boolean allowsLearning()
  {
    return allowLearning;
  }
}
