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
      List<String> distance = word.length() < 3 || candidates.size() + 1 >= max
        ? Collections.emptyList() : source.distance(word, 1, max);
      for (int i = 0; i < max && candidates.size() < max; i++)
      {
        if (i < suffixLookup.suffixes.size())
          add(candidates, candidate(suffixLookup.suffixes.get(i), request,
                CandidateType.COMPLETION, SOURCE_CDICT), max);
        if (i < distance.size())
          add(candidates, candidate(distance.get(i), request,
                CandidateType.CORRECTION, SOURCE_CDICT), max);
      }
    }

    if (personal != null)
      prependPersonal(candidates, personal.findPrefix(word, 2), request, typedWord, max);
    boolean capitalize = Character.isUpperCase(typedWord.codePointAt(0))
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

  private static void prependPersonal(ArrayList<PredictionCandidate> candidates,
      String[] personal, PredictionRequest request, String typedWord, int max)
  {
    ArrayList<PredictionCandidate> merged = new ArrayList<>(max);
    for (int i = 0; i < personal.length && i < 2 && merged.size() < max; i++)
      if (!contains(candidates, personal[i]) && !contains(merged, personal[i]))
        merged.add(candidate(personal[i], request,
              personalType(personal[i], typedWord), SOURCE_PERSONAL));
    for (int i = 0; i < candidates.size() && merged.size() < max; i++)
      if (!contains(merged, candidates.get(i).getText()))
        merged.add(candidates.get(i));
    candidates.clear();
    candidates.addAll(merged);
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
    List<String> distance(String word, int maxDistance, int maxResults);
  }

  interface PersonalSource
  {
    String[] findPrefix(String prefix, int maxResults);
  }

  static final class Lookup
  {
    final String exact;
    final boolean hasPrefix;
    final List<String> suffixes;

    Lookup(String exact, boolean hasPrefix, List<String> suffixes)
    {
      this.exact = exact;
      this.hasPrefix = hasPrefix;
      this.suffixes = suffixes;
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
      int[] indexes = dictionary.suffixes(result, maxResults);
      ArrayList<String> suffixes = new ArrayList<>(indexes.length);
      for (int index : indexes)
        suffixes.add(dictionary.word(index));
      return new Lookup(result.found ? dictionary.word(result.index) : null,
          result.prefix_ptr != 0, suffixes);
    }

    public List<String> distance(String word, int maxDistance, int maxResults)
    {
      int[] indexes = dictionary.distance(word, maxDistance, maxResults);
      ArrayList<String> words = new ArrayList<>(indexes.length);
      for (int index : indexes)
        words.add(dictionary.word(index));
      return words;
    }
  }
}
