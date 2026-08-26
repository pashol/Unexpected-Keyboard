package juloo.keyboard2.prediction;

import java.util.Locale;

/** The bundled development fixture only represents English data. */
public final class DevelopmentPredictionPack
{
  private DevelopmentPredictionPack() {}

  public static boolean supports_locale(String languageTag)
  {
    return languageTag != null
      && "en".equals(Locale.forLanguageTag(languageTag).getLanguage());
  }
}
