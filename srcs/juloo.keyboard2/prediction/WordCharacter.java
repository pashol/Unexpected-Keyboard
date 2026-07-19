package juloo.keyboard2.prediction;

public final class WordCharacter
{
  private WordCharacter()
  {
  }

  public static boolean isWordChar(int codePoint)
  {
    return Character.isLetterOrDigit(codePoint) || codePoint == '\'';
  }
}
