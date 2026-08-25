package juloo.keyboard2.prediction;

import java.util.Locale;

/** Registry-backed production packs currently expose the reviewed Swiss German asset. */
public final class ProductionPredictionPack
{
  private ProductionPredictionPack() {}

  public static String asset_for_locale(String languageTag)
  {
    if (languageTag == null)
      return null;
    return "gsw".equals(Locale.forLanguageTag(languageTag).getLanguage())
      ? "gsw.dict" : null;
  }
}
