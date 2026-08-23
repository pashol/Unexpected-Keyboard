package juloo.keyboard2.prediction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class PredictionRequest
{
  private static final int MAX_PRECEDING_WORDS = 3;

  private final String composingText;
  private final int composingCursorCodePoint;
  private final List<String> precedingWords;
  private final boolean sentenceStart;
  private final String languageTag;
  private final int maxResults;
  private final long generation;
  private final EditorPredictionPolicy editorPredictionPolicy;
  private final List<KeyCenter> keyCenters;

  public static final class KeyCenter
  {
    private final int codePoint;
    private final int x;
    private final int y;

    public KeyCenter(int codePoint, int x, int y)
    {
      this.codePoint = codePoint;
      this.x = x;
      this.y = y;
    }

    public int getCodePoint() { return codePoint; }
    public int getX() { return x; }
    public int getY() { return y; }
  }

  public PredictionRequest(
      String composingText,
      int composingCursorCodePoint,
      List<String> precedingWords,
      boolean sentenceStart,
      String languageTag,
      int maxResults,
      long generation,
      EditorPredictionPolicy editorPredictionPolicy)
  {
    this(composingText, composingCursorCodePoint, precedingWords, sentenceStart,
        languageTag, maxResults, generation, editorPredictionPolicy,
        Collections.<KeyCenter>emptyList());
  }

  public PredictionRequest(
      String composingText,
      int composingCursorCodePoint,
      List<String> precedingWords,
      boolean sentenceStart,
      String languageTag,
      int maxResults,
      long generation,
      EditorPredictionPolicy editorPredictionPolicy,
      List<KeyCenter> keyCenters)
  {
    this.composingText = Objects.requireNonNull(composingText, "composingText");
    if (composingCursorCodePoint < 0 || composingCursorCodePoint >
        composingText.codePointCount(0, composingText.length()))
      throw new IllegalArgumentException("composingCursorCodePoint is outside composingText");
    this.composingCursorCodePoint = composingCursorCodePoint;
    Objects.requireNonNull(precedingWords, "precedingWords");
    int firstWord = Math.max(0, precedingWords.size() - MAX_PRECEDING_WORDS);
    List<String> words = new ArrayList<>(precedingWords.size() - firstWord);
    for (int i = firstWord; i < precedingWords.size(); i++)
      words.add(Objects.requireNonNull(precedingWords.get(i), "precedingWords element"));
    this.precedingWords = Collections.unmodifiableList(words);
    this.sentenceStart = sentenceStart;
    this.languageTag = Objects.requireNonNull(languageTag, "languageTag");
    if (maxResults <= 0)
      throw new IllegalArgumentException("maxResults must be positive");
    this.maxResults = maxResults;
    this.generation = generation;
    this.editorPredictionPolicy = Objects.requireNonNull(
        editorPredictionPolicy, "editorPredictionPolicy");
    Objects.requireNonNull(keyCenters, "keyCenters");
    this.keyCenters = Collections.unmodifiableList(new ArrayList<KeyCenter>(keyCenters));
  }

  public String getComposingText()
  {
    return composingText;
  }

  public int getComposingCursorCodePoint()
  {
    return composingCursorCodePoint;
  }

  public List<String> getPrecedingWords()
  {
    return precedingWords;
  }

  public boolean isSentenceStart()
  {
    return sentenceStart;
  }

  public String getLanguageTag()
  {
    return languageTag;
  }

  public int getMaxResults()
  {
    return maxResults;
  }

  public long getGeneration()
  {
    return generation;
  }

  public EditorPredictionPolicy getEditorPredictionPolicy()
  {
    return editorPredictionPolicy;
  }

  public List<KeyCenter> getKeyCenters()
  {
    return keyCenters;
  }
}
