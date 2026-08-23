package juloo.keyboard2.prediction;

import com.android.inputmethod.keyboard.ProximityInfo;
import com.android.inputmethod.latin.BinaryDictionary;
import com.android.inputmethod.latin.DicTraverseSession;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import juloo.keyboard2.Utils;
import juloo.keyboard2.prediction.history.UserHistoryModel;
import juloo.keyboard2.prediction.history.UserHistoryPersistence;

/** Immutable owner of one native LatinIME pack. */
public final class LatinPredictionDecoder implements PredictionEngine
{
  static final int NATIVE_TYPED = 0;
  static final int NATIVE_CORRECTION = 1;
  static final int NATIVE_COMPLETION = 2;
  static final int NATIVE_SHORTCUT = 7;
  static final int NATIVE_PREDICTION = 8;

  interface NativeFacade extends AutoCloseable
  {
    List<NativeResult> suggest(int[] input, int[] x, int[] y,
        List<PredictionRequest.KeyCenter> keyCenters, int[][] preceding,
        boolean sentenceStart, int maxResults);
    void close();
  }

  static final class NativeResult
  {
    final int[] codePoints;
    final int lexicalProbability;
    final int contextProbability;
    final int type;

    NativeResult(int[] codePoints, int lexicalProbability, int contextProbability, int type)
    {
      this.codePoints = codePoints;
      this.lexicalProbability = lexicalProbability;
      this.contextProbability = contextProbability;
      this.type = type;
    }
  }

  private final NativeFacade nativeFacade;
  private final String languageTag;
  private final UserHistoryModel history;
  private final UserHistoryPersistence persistence;
  private PredictionRequest lastRequest;
  private boolean closed;

  LatinPredictionDecoder(NativeFacade nativeFacade, String languageTag)
  {
    this(nativeFacade, languageTag, UserHistoryModel.empty(System::currentTimeMillis));
  }

  LatinPredictionDecoder(NativeFacade nativeFacade, String languageTag, UserHistoryModel history)
  {
    this.nativeFacade = Objects.requireNonNull(nativeFacade, "nativeFacade");
    this.languageTag = Objects.requireNonNull(languageTag, "languageTag");
    this.history = Objects.requireNonNull(history, "history");
    persistence = null;
  }

  LatinPredictionDecoder(NativeFacade nativeFacade, String languageTag,
      UserHistoryPersistence persistence)
  {
    this.nativeFacade = Objects.requireNonNull(nativeFacade, "nativeFacade");
    this.languageTag = Objects.requireNonNull(languageTag, "languageTag");
    this.persistence = Objects.requireNonNull(persistence, "persistence");
    history = null;
  }

  public LatinPredictionDecoder(File pack, String languageTag,
      List<PredictionRequest.KeyCenter> keyCenters)
  {
    this(new NativePack(pack, languageTag, keyCenters), languageTag);
  }

  LatinPredictionDecoder(File pack, String languageTag,
      List<PredictionRequest.KeyCenter> keyCenters, UserHistoryPersistence persistence)
  {
    this(new NativePack(pack, languageTag, keyCenters), languageTag, persistence);
  }

  @Override
  public List<PredictionCandidate> predict(PredictionRequest request)
  {
    if (closed || !request.getEditorPredictionPolicy().allowsPrediction())
      return Collections.emptyList();
    lastRequest = request;
    int[] input = codePoints(request.getComposingText());
    Map<Integer, PredictionRequest.KeyCenter> centers = centers(request.getKeyCenters());
    int[][] preceding = preceding(request.getPrecedingWords());
    ArrayList<PredictionCandidate> candidates = new ArrayList<PredictionCandidate>();
    boolean capitalize = shouldCapitalize(request);
    String typedText = capitalize ? Utils.capitalize_string(request.getComposingText())
        : request.getComposingText();
    if (input.length > 0)
      candidates.add(candidate(typedText, request, CandidateType.TYPED, 0, 0, 0));
    for (NativeResult result : nativeFacade.suggest(input, coordinates(input, centers, true),
        coordinates(input, centers, false), request.getKeyCenters(), preceding,
        request.isSentenceStart(), request.getMaxResults()))
    {
      String text = new String(result.codePoints, 0, result.codePoints.length);
      CandidateType type = type(result.type, text, request.getComposingText());
      if (type == CandidateType.CORRECTION && !request.getEditorPredictionPolicy().allowsCorrection())
        continue;
      if (capitalize)
        text = Utils.capitalize_string(text);
      if (!contains(candidates, text))
        candidates.add(candidate(text, request, type, result.lexicalProbability,
            result.contextProbability, touchCost(input, result.codePoints, centers)));
    }
    List<PredictionCandidate> ranked = CandidateRanker.rank(candidates, typedText);
    return Collections.unmodifiableList(new ArrayList<PredictionCandidate>(
        ranked.subList(0, Math.min(request.getMaxResults(), ranked.size()))));
  }

  @Override public void recordFeedback(PredictionFeedback feedback)
  {
    if (persistence == null || lastRequest == null || !lastRequest.getEditorPredictionPolicy().allowsLearning())
      return;
    String text = feedback.getType() == FeedbackType.COMMITTED ? feedback.getCommittedText()
        : feedback.getCandidate().getText();
    switch (feedback.getType()) {
      case ACCEPTED:
      case COMMITTED: persistence.accept(lastRequest.getPrecedingWords(), text, true); break;
      case REJECTED: persistence.reject(lastRequest.getPrecedingWords(), text, true); break;
      case REVERTED: persistence.revert(lastRequest.getPrecedingWords(), text, true); break;
    }
  }
  @Override public void resetSession() {}
  @Override public void close()
  {
    if (!closed) { closed = true; nativeFacade.close(); if (persistence != null) persistence.close(); }
  }

  private PredictionCandidate candidate(String text, PredictionRequest request, CandidateType type,
      double lexical, double context, double touchCost)
  {
    return new PredictionCandidate(text, languageTag, type, "latinime", lexical, context,
        touchCost, historyScore(request, text), 0);
  }

  private double historyScore(PredictionRequest request, String text)
  {
    List<String> preceding = request.getPrecedingWords();
    UserHistoryModel current = persistence == null ? history : persistence.snapshot();
    double result = current.unigramScore(text);
    int size = preceding.size();
    if (size > 0) result += current.bigramScore(preceding.get(size - 1), text);
    if (size > 1) result += current.trigramScore(preceding.get(size - 2), preceding.get(size - 1), text);
    return result;
  }

  private static CandidateType type(int nativeType, String text, String input)
  {
    if (text.equals(input)) return CandidateType.TYPED;
    switch (nativeType & 0xff) {
      case NATIVE_COMPLETION: return CandidateType.COMPLETION;
      case NATIVE_PREDICTION: return CandidateType.NEXT_WORD;
      case NATIVE_SHORTCUT: return CandidateType.SHORTCUT;
      default: return CandidateType.CORRECTION;
    }
  }

  private static boolean shouldCapitalize(PredictionRequest request)
  {
    String text = request.getComposingText();
    return text.length() > 0 && (Character.isUpperCase(text.codePointAt(0))
        || request.isSentenceStart());
  }

  private static boolean contains(List<PredictionCandidate> candidates, String text)
  {
    for (PredictionCandidate candidate : candidates)
      if (candidate.getText().equals(text)) return true;
    return false;
  }

  private static int[][] preceding(List<String> words)
  {
    int[][] result = new int[words.size()][];
    for (int i = 0; i < words.size(); i++) result[i] = codePoints(words.get(i));
    return result;
  }

  private static int[] codePoints(String text)
  {
    int[] result = new int[text.codePointCount(0, text.length())];
    for (int offset = 0, i = 0; offset < text.length(); i++) {
      int codePoint = Character.codePointAt(text, offset);
      result[i] = codePoint;
      offset += Character.charCount(codePoint);
    }
    return result;
  }

  private static Map<Integer, PredictionRequest.KeyCenter> centers(
      List<PredictionRequest.KeyCenter> keyCenters)
  {
    Map<Integer, PredictionRequest.KeyCenter> result = new HashMap<Integer, PredictionRequest.KeyCenter>();
    for (PredictionRequest.KeyCenter center : keyCenters) result.put(center.getCodePoint(), center);
    return result;
  }

  private static int[] coordinates(int[] input,
      Map<Integer, PredictionRequest.KeyCenter> centers, boolean horizontal)
  {
    int[] result = new int[input.length];
    for (int i = 0; i < input.length; i++) {
      PredictionRequest.KeyCenter center = centers.get(input[i]);
      result[i] = center == null ? 0 : horizontal ? center.getX() : center.getY();
    }
    return result;
  }

  private static double touchCost(int[] input, int[] candidate,
      Map<Integer, PredictionRequest.KeyCenter> centers)
  {
    double result = Math.abs(input.length - candidate.length);
    for (int i = 0; i < input.length && i < candidate.length; i++)
      if (input[i] != candidate[i]) {
        PredictionRequest.KeyCenter from = centers.get(input[i]);
        PredictionRequest.KeyCenter to = centers.get(candidate[i]);
        result += from == null || to == null ? 1
          : Math.hypot(from.getX() - to.getX(), from.getY() - to.getY());
      }
    return result;
  }

  interface TraverseSession extends AutoCloseable
  {
    void initialize(long dictionary, int[] locale, int flags);
    long handle();
    void close();
  }

  interface NativePackResources
  {
    LatinDecoder.Dictionary open(File pack);
    TraverseSession createSession(String languageTag, long size);
  }

  /** Bridges the immutable Java pack owner to the pinned LatinIME JNI API. */
  static final class NativePack implements NativeFacade
  {
    private static final int MAX_RESULTS = 18;
    private static final int MAX_WORD_LENGTH = 48;
    private static final int MAX_PROXIMITY_CHARS = 16;
    private final LatinDecoder.Dictionary dictionary;
    private final TraverseSession session;
    private boolean closed;

    NativePack(File pack, String languageTag, List<PredictionRequest.KeyCenter> centers)
    {
      this(new NativePackResources() {
        public LatinDecoder.Dictionary open(File value) { return new LatinDecoder().open(value); }
        public TraverseSession createSession(String tag, long size)
        {
          final DicTraverseSession session = new DicTraverseSession(tag, size);
          return new TraverseSession() {
            public void initialize(long dictionary, int[] locale, int flags)
            { session.initialize(dictionary, locale, flags); }
            public long handle() { return session.handle(); }
            public void close() { session.close(); }
          };
        }
      }, pack, languageTag);
    }

    NativePack(NativePackResources resources, File pack, String languageTag)
    {
      LatinDecoder.Dictionary opened = resources.open(pack);
      TraverseSession created = null;
      try {
        created = resources.createSession(languageTag, pack.length());
        created.initialize(opened.handle(), null, 0);
      } catch (RuntimeException | Error failure) {
        try { if (created != null) created.close(); }
        finally { opened.close(); }
        throw failure;
      }
      dictionary = opened;
      session = created;
    }

    public List<NativeResult> suggest(int[] input, int[] x, int[] y,
        List<PredictionRequest.KeyCenter> keyCenters, int[][] preceding,
        boolean sentenceStart, int maxResults)
    {
      int[] count = new int[1];
      int[] output = new int[MAX_RESULTS * MAX_WORD_LENGTH];
      int[] scores = new int[MAX_RESULTS];
      int[] indices = new int[MAX_RESULTS];
      int[] types = new int[MAX_RESULTS];
      boolean[] beginning = new boolean[preceding.length];
      if (beginning.length > 0) beginning[0] = sentenceStart;
      ProximityInfo proximity = proximity(keyCenters);
      try {
      BinaryDictionary.getSuggestionsNative(dictionary.handle(), proximity.handle(), session.handle(),
          x, y, new int[input.length], new int[input.length], input, input.length,
          new int[] { 0, 1, 0, 0, 1000 }, preceding, beginning, preceding.length, count,
          output, scores, indices, types, new int[1], new float[] { 1f });
      ArrayList<NativeResult> results = new ArrayList<NativeResult>();
      for (int i = 0; i < count[0] && i < maxResults; i++) {
        int start = i * MAX_WORD_LENGTH;
        int length = 0;
        while (length < MAX_WORD_LENGTH && output[start + length] != 0) length++;
        int[] word = Arrays.copyOfRange(output, start, start + length);
        results.add(new NativeResult(word,
            BinaryDictionary.getProbabilityNative(dictionary.handle(), word),
            BinaryDictionary.getNgramProbabilityNative(dictionary.handle(), preceding, beginning, word),
            types[i]));
      }
      return results;
      } finally { proximity.close(); }
    }

    public void close()
    {
      if (!closed) {
        closed = true;
        session.close();
        dictionary.close();
      }
    }

    private static ProximityInfo proximity(List<PredictionRequest.KeyCenter> centers)
    {
      int size = centers.size();
      int[] x = new int[size], y = new int[size], widths = new int[size], heights = new int[size];
      int[] codes = new int[size];
      float[] sweetX = new float[size], sweetY = new float[size], radii = new float[size];
      int width = 1, height = 1;
      for (int i = 0; i < size; i++) {
        PredictionRequest.KeyCenter center = centers.get(i);
        x[i] = center.getX(); y[i] = center.getY(); codes[i] = center.getCodePoint();
        widths[i] = heights[i] = 1; sweetX[i] = x[i]; sweetY[i] = y[i]; radii[i] = 1;
        width = Math.max(width, x[i] + 1); height = Math.max(height, y[i] + 1);
      }
      return new ProximityInfo(width, height, 1, 1, 1, 1,
          new int[MAX_PROXIMITY_CHARS], size, x, y, widths, heights, codes,
          sweetX, sweetY, radii);
    }
  }
}
