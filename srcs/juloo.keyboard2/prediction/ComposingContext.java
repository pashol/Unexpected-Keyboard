package juloo.keyboard2.prediction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class ComposingContext
{
  private static final int MAX_PRECEDING_WORDS = 3;

  public final String composingText;
  public final int composingCursorCodePoint;
  public final List<String> precedingWords;
  public final boolean sentenceStart;
  public final boolean contextKnown;

  public ComposingContext(
      String composingText,
      int composingCursorCodePoint,
      List<String> precedingWords,
      boolean sentenceStart,
      boolean contextKnown)
  {
    this.composingText = Objects.requireNonNull(composingText, "composingText");
    if (composingCursorCodePoint < 0 || composingCursorCodePoint >
        composingText.codePointCount(0, composingText.length()))
      throw new IllegalArgumentException(
          "composingCursorCodePoint is outside composingText");
    this.composingCursorCodePoint = composingCursorCodePoint;
    Objects.requireNonNull(precedingWords, "precedingWords");
    int firstWord = Math.max(0, precedingWords.size() - MAX_PRECEDING_WORDS);
    List<String> words = new ArrayList<>(precedingWords.size() - firstWord);
    for (int i = firstWord; i < precedingWords.size(); i++)
      words.add(Objects.requireNonNull(
          precedingWords.get(i), "precedingWords element"));
    this.precedingWords = Collections.unmodifiableList(words);
    this.sentenceStart = sentenceStart;
    this.contextKnown = contextKnown;
  }
}
