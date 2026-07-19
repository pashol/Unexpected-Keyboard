package juloo.keyboard2.prediction;

import java.util.Objects;

public final class PredictionCandidate
{
  private final String text;
  private final String languageTag;
  private final CandidateType type;
  private final String source;
  private final double lexicalScore;
  private final double contextScore;
  private final double touchEditCost;
  private final double personalizationScore;
  private final double autocorrectConfidence;

  public PredictionCandidate(
      String text,
      String languageTag,
      CandidateType type,
      String source,
      double lexicalScore,
      double contextScore,
      double touchEditCost,
      double personalizationScore,
      double autocorrectConfidence)
  {
    this.text = Objects.requireNonNull(text, "text");
    this.languageTag = Objects.requireNonNull(languageTag, "languageTag");
    this.type = Objects.requireNonNull(type, "type");
    this.source = Objects.requireNonNull(source, "source");
    this.lexicalScore = lexicalScore;
    this.contextScore = contextScore;
    this.touchEditCost = touchEditCost;
    this.personalizationScore = personalizationScore;
    this.autocorrectConfidence = autocorrectConfidence;
  }

  public String getText()
  {
    return text;
  }

  public String getLanguageTag()
  {
    return languageTag;
  }

  public CandidateType getType()
  {
    return type;
  }

  public String getSource()
  {
    return source;
  }

  public double getLexicalScore()
  {
    return lexicalScore;
  }

  public double getContextScore()
  {
    return contextScore;
  }

  public double getTouchEditCost()
  {
    return touchEditCost;
  }

  public double getPersonalizationScore()
  {
    return personalizationScore;
  }

  public double getAutocorrectConfidence()
  {
    return autocorrectConfidence;
  }
}
