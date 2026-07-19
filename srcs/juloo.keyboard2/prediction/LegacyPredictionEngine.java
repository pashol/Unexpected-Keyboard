package juloo.keyboard2.prediction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import juloo.cdict.Cdict;
import juloo.keyboard2.ComposeKey;
import juloo.keyboard2.ComposeKeyData;
import juloo.keyboard2.Config;
import juloo.keyboard2.Utils;
import juloo.keyboard2.suggestions.UserDictionary;

/** The existing cdict and personal-dictionary candidate generation pipeline. */
public final class LegacyPredictionEngine implements PredictionEngine
{
  public static final String SOURCE_CDICT = "legacy-cdict";
  public static final String SOURCE_PERSONAL = "legacy-personal";
  public static final String SOURCE_TYPED = "legacy-typed";

  private final Config config;
  private final CandidateSource candidateSource;
  private final PersonalSource personalSource;
  private final boolean personalEnabled;
  private final boolean capitalizeAtSentenceStart;

  public LegacyPredictionEngine(Config config)
  {
    this.config = config;
    candidateSource = null;
    personalSource = null;
    personalEnabled = false;
    capitalizeAtSentenceStart = false;
  }

  LegacyPredictionEngine(CandidateSource candidateSource,
      PersonalSource personalSource, boolean personalEnabled,
      boolean capitalizeAtSentenceStart)
  {
    config = null;
    this.candidateSource = candidateSource;
    this.personalSource = personalSource;
    this.personalEnabled = personalEnabled;
    this.capitalizeAtSentenceStart = capitalizeAtSentenceStart;
  }

  @Override
  public List<PredictionCandidate> predict(PredictionRequest request)
  {
    String typedWord = request.getComposingText();
    if (typedWord.length() == 0)
      return Collections.emptyList();
    CandidateSource source = source();
    PersonalSource personal = personalSource();
    int max = request.getMaxResults();
    String word = applySubstitutions(typedWord);
    ArrayList<PredictionCandidate> candidates = new ArrayList<>(max);

    if (personal != null)
      addPersonal(candidates, personal.findPrefix(word, 2), request, typedWord, max);

    if (source != null)
    {
      Lookup exact = source.find(word, max);
      add(candidates, candidate(exact.exact, request,
            exactType(exact.exact, typedWord), SOURCE_CDICT), max);
      Lookup suffixLookup = exact;
      if (!exact.hasPrefix)
      {
        Lookup alternate = source.find(alternateFirstCharacter(word), max);
        add(candidates, candidate(alternate.exact, request,
              exactType(alternate.exact, typedWord), SOURCE_CDICT), max);
        suffixLookup = alternate;
      }
      CandidateSequence suffixes = source.suffixes(suffixLookup, max);
      CandidateSequence distance = word.length() < 3 || candidates.size() + 1 >= max
        ? EMPTY_SEQUENCE : source.distance(word, 1, max);
      for (int i = 0; i < max && candidates.size() < max; i++)
      {
        if (i < suffixes.size())
          add(candidates, candidate(suffixes.resolve(i), request,
                CandidateType.COMPLETION, SOURCE_CDICT), max);
        if (i < distance.size() && candidates.size() < max)
          add(candidates, candidate(distance.resolve(i), request,
                CandidateType.CORRECTION, SOURCE_CDICT), max);
      }
    }

    boolean capitalize = Character.isUpperCase(typedWord.charAt(0))
      || (request.isSentenceStart() && capitalizeAtSentenceStart());
    if (capitalize)
      capitalize(candidates);
    promoteTypedWord(candidates, candidate(
          capitalize ? Utils.capitalize_string(typedWord) : typedWord,
          request, CandidateType.TYPED, SOURCE_TYPED), max);
    return Collections.unmodifiableList(candidates);
  }

  @Override
  public void recordFeedback(PredictionFeedback feedback) {}

  @Override
  public void resetSession() {}

  @Override
  public void close() {}

  private CandidateSource source()
  {
    if (config != null)
      return config.current_dictionary == null
        ? null : new CdictCandidateSource(config.current_dictionary);
    return candidateSource;
  }

  private PersonalSource personalSource()
  {
    if (config != null)
    {
      UserDictionary dictionary = UserDictionary.instance();
      return config.user_dictionary_enabled && dictionary != null
        ? dictionary::find_prefix : null;
    }
    return personalEnabled ? personalSource : null;
  }

  private boolean capitalizeAtSentenceStart()
  {
    return config != null
      ? config.capitalize_suggestions_at_sentence_start
      : capitalizeAtSentenceStart;
  }

  private static void addPersonal(ArrayList<PredictionCandidate> candidates,
      String[] personal, PredictionRequest request, String typedWord, int max)
  {
    for (int i = 0; i < personal.length && i < 2 && candidates.size() < max; i++)
      if (!contains(candidates, personal[i]))
        candidates.add(candidate(personal[i], request,
              personalType(personal[i], typedWord), SOURCE_PERSONAL));
  }

  private static void capitalize(ArrayList<PredictionCandidate> candidates)
  {
    for (int i = 0; i < candidates.size(); i++)
      candidates.set(i, withText(candidates.get(i),
            Utils.capitalize_string(candidates.get(i).getText())));
  }

  private static void promoteTypedWord(ArrayList<PredictionCandidate> candidates,
      PredictionCandidate typed, int max)
  {
    int matchingIndex = -1;
    for (int i = 0; i < candidates.size(); i++)
      if (candidates.get(i).getText().equalsIgnoreCase(typed.getText()))
        matchingIndex = i;
    if (matchingIndex == 0 || (matchingIndex == -1 && candidates.size() < 2))
      return;
    if (matchingIndex > 0)
    {
      PredictionCandidate matching = candidates.remove(matchingIndex);
      candidates.add(0, matching);
      return;
    }
    candidates.add(0, typed);
    if (candidates.size() > max)
      candidates.remove(max);
  }

  private static void add(ArrayList<PredictionCandidate> candidates,
      PredictionCandidate candidate, int max)
  {
    if (candidate != null && candidates.size() < max
        && !contains(candidates, candidate.getText()))
      candidates.add(candidate);
  }

  private static boolean contains(List<PredictionCandidate> candidates, String text)
  {
    for (PredictionCandidate candidate : candidates)
      if (candidate.getText().equalsIgnoreCase(text))
        return true;
    return false;
  }

  private static CandidateType exactType(String result, String typedWord)
  {
    return result != null && result.equalsIgnoreCase(typedWord)
      ? CandidateType.TYPED : CandidateType.CORRECTION;
  }

  private static CandidateType personalType(String result, String typedWord)
  {
    return result.equalsIgnoreCase(typedWord)
      ? CandidateType.TYPED : CandidateType.COMPLETION;
  }

  private static PredictionCandidate candidate(String text,
      PredictionRequest request, CandidateType type, String source)
  {
    if (text == null)
      return null;
    return new PredictionCandidate(text, request.getLanguageTag(), type, source,
        0, 0, type == CandidateType.CORRECTION ? 1 : 0,
        SOURCE_PERSONAL.equals(source) ? 1 : 0, 0);
  }

  private static PredictionCandidate withText(PredictionCandidate candidate, String text)
  {
    return new PredictionCandidate(text, candidate.getLanguageTag(), candidate.getType(),
        candidate.getSource(), candidate.getLexicalScore(), candidate.getContextScore(),
        candidate.getTouchEditCost(), candidate.getPersonalizationScore(),
        candidate.getAutocorrectConfidence());
  }

  public static String alternateFirstCharacter(String word)
  {
    int firstCharEnd = word.offsetByCodePoints(0, 1);
    String firstChar = word.substring(0, firstCharEnd);
    String rest = word.substring(firstCharEnd);
    return Character.isUpperCase(word.codePointAt(0))
      ? firstChar.toLowerCase(Locale.getDefault()) + rest
      : Utils.capitalize_string(word);
  }

  private static String applySubstitutions(String word)
  {
    StringBuilder result = new StringBuilder(word);
    for (int i = 0; i < word.length(); i++)
    {
      char replacement = ComposeKey.transform_char(
          ComposeKeyData.substitutions, result.charAt(i));
      if (replacement != 0)
        result.setCharAt(i, replacement);
    }
    return result.toString();
  }

  interface CandidateSource
  {
    Lookup find(String word, int maxResults);
    CandidateSequence suffixes(Lookup lookup, int maxResults);
    CandidateSequence distance(String word, int maxDistance, int maxResults);
  }

  interface CandidateSequence
  {
    int size();
    String resolve(int index);
  }

  interface PersonalSource
  {
    String[] findPrefix(String prefix, int maxResults);
  }

  static final class Lookup
  {
    final String exact;
    final boolean hasPrefix;
    final Object token;

    Lookup(String exact, boolean hasPrefix, Object token)
    {
      this.exact = exact;
      this.hasPrefix = hasPrefix;
      this.token = token;
    }
  }

  private static final class CdictCandidateSource implements CandidateSource
  {
    private final Cdict dictionary;

    CdictCandidateSource(Cdict dictionary)
    {
      this.dictionary = dictionary;
    }

    public Lookup find(String word, int maxResults)
    {
      Cdict.Result result = dictionary.find(word);
      return new Lookup(result.found ? dictionary.word(result.index) : null,
          result.prefix_ptr != 0, result);
    }

    public CandidateSequence suffixes(Lookup lookup, int maxResults)
    {
      Cdict.Result result = (Cdict.Result)lookup.token;
      return new CdictCandidateSequence(
          dictionary, dictionary.suffixes(result, maxResults));
    }

    public CandidateSequence distance(String word, int maxDistance, int maxResults)
    {
      return new CdictCandidateSequence(
          dictionary, dictionary.distance(word, maxDistance, maxResults));
    }
  }

  private static final class CdictCandidateSequence implements CandidateSequence
  {
    private final Cdict dictionary;
    private final int[] indexes;

    CdictCandidateSequence(Cdict dictionary, int[] indexes)
    {
      this.dictionary = dictionary;
      this.indexes = indexes;
    }

    public int size() { return indexes.length; }
    public String resolve(int index) { return dictionary.word(indexes[index]); }
  }

  private static final CandidateSequence EMPTY_SEQUENCE = new CandidateSequence()
  {
    public int size() { return 0; }
    public String resolve(int index) { throw new IndexOutOfBoundsException(); }
  };
}
