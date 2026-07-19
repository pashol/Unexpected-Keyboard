package juloo.keyboard2.prediction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class PrecedingContextExtractor
{
  private static final int MAX_PRECEDING_WORDS = 3;

  private PrecedingContextExtractor()
  {
  }

  public static List<String> extract(
      String textBeforeCursor, int composingPrefixChars)
  {
    Objects.requireNonNull(textBeforeCursor, "textBeforeCursor");
    if (composingPrefixChars < 0 || composingPrefixChars > textBeforeCursor.length())
      throw new IllegalArgumentException(
          "composingPrefixChars is outside textBeforeCursor");
    int contextEnd = textBeforeCursor.length() - composingPrefixChars;
    if (contextEnd > 0 && contextEnd < textBeforeCursor.length() &&
        Character.isLowSurrogate(textBeforeCursor.charAt(contextEnd)) &&
        Character.isHighSurrogate(textBeforeCursor.charAt(contextEnd - 1)))
      throw new IllegalArgumentException("composingPrefixChars splits a code point");

    List<String> words = new ArrayList<>();
    int wordStart = -1;
    for (int i = 0; i < contextEnd;)
    {
      int codePoint = Character.codePointAt(textBeforeCursor, i);
      if (isWordChar(codePoint))
      {
        if (wordStart < 0)
          wordStart = i;
      }
      else if (wordStart >= 0)
      {
        addWord(words, textBeforeCursor.substring(wordStart, i));
        wordStart = -1;
      }
      i += Character.charCount(codePoint);
    }
    if (wordStart >= 0)
      addWord(words, textBeforeCursor.substring(wordStart, contextEnd));
    return Collections.unmodifiableList(words);
  }

  private static void addWord(List<String> words, String word)
  {
    if (words.size() == MAX_PRECEDING_WORDS)
      words.remove(0);
    words.add(word);
  }

  private static boolean isWordChar(int codePoint)
  {
    return Character.isLetterOrDigit(codePoint) || codePoint == '\'';
  }
}
