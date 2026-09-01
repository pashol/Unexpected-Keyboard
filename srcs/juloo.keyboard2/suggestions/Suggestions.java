package juloo.keyboard2.suggestions;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import juloo.cdict.Cdict;
import juloo.keyboard2.Config;
import juloo.keyboard2.ComposeKey;
import juloo.keyboard2.ComposeKeyData;
import juloo.keyboard2.Logs;
import juloo.keyboard2.Utils;
import juloo.keyboard2.prediction.PredictionCandidate;
import juloo.keyboard2.prediction.PredictionEngineController;
import juloo.keyboard2.prediction.PredictionRequest;

/** Keep track of the word being typed and provide suggestions for
    [CandidatesView]. */
public final class Suggestions
{
  /** Number of suggestions in [suggestions]. */
  public static final int MAX_COUNT = 3;

  public static enum CandidateType
  {
    COMPLETION,
    NEXT_WORD
  }

  public static enum CandidateCase
  {
    DEFAULT,
    TITLE,
    UPPER
  }

  Callback _callback;
  Config _config;
  boolean _enabled;
  private PredictionEngineController _prediction_controller;
  private int _request_generation;
  private CandidateCase _candidate_case = CandidateCase.DEFAULT;

  /** Current suggestions. The best suggestion is at index [0]. */
  public String[] suggestions = new String[MAX_COUNT];
  /** Whether each suggestion slot was sourced from the personal dictionary. */
  public boolean[] personal_suggestions = new boolean[MAX_COUNT];
  /** How each suggestion slot is committed. */
  public CandidateType[] types = new CandidateType[MAX_COUNT];
  /** Number of suggestions at the beginning of the [suggestions] array that
      are not [null]. */
  public int count = 0;
  public String emoji_suggestion = null;
  public Suggestions(Callback c, Config conf)
  {
    this(c, conf, null);
  }

  public Suggestions(Callback c, Config conf, PredictionEngineController predictionController)
  {
    _callback = c;
    _config = conf;
    _prediction_controller = predictionController;
    clear();
  }

  public void set_prediction_controller(PredictionEngineController predictionController)
  {
    _prediction_controller = predictionController;
    clear();
  }

  public boolean set_candidate_case(CandidateCase candidateCase)
  {
    if (_candidate_case == candidateCase)
      return false;
    _candidate_case = candidateCase;
    return true;
  }

  public void started()
  {
    _enabled = _config.editor_config.should_show_candidates_view;
    clear();
  }

  public void currently_typed_word(String word, boolean sentence_start)
  {
    currently_typed_word(word, sentence_start, Collections.<String>emptyList());
  }

  public void currently_typed_word(String word, boolean sentence_start,
      List<String> preceding_words)
  {
    int generation = ++_request_generation;
    int candidate_count = 0;
    if (!_enabled)
    {
      Logs.debug("NextWord: routing emptyToken=" + (word.length() == 0)
          + " precedingWordCount=" + preceding_words.size()
          + " controllerPresent=" + (_prediction_controller != null)
          + " candidateCount=" + candidate_count);
      return;
    }
    if (word.length() == 0 && !preceding_words.isEmpty()
        && _prediction_controller != null)
    {
      List<PredictionCandidate> candidates = _prediction_controller.predict(
          new PredictionRequest(preceding_words, MAX_COUNT, generation));
      candidate_count = candidates.size();
      if (generation == _request_generation)
        set_next_word_candidates(candidates);
    }
    else if (word.length() < 2 || (_config.current_dictionary == null
          && (!_config.user_dictionary_enabled || UserDictionary.instance() == null)))
      clear();
    else
      query_suggestions(word, sentence_start);
    Logs.debug("NextWord: routing emptyToken=" + (word.length() == 0)
        + " precedingWordCount=" + preceding_words.size()
        + " controllerPresent=" + (_prediction_controller != null)
        + " candidateCount=" + candidate_count);
    if (_callback != null)
      _callback.set_suggestions(this);
  }

  void clear()
  {
    count = 0;
    for (int i = 0; i < MAX_COUNT; i++)
    {
      suggestions[i] = null;
      personal_suggestions[i] = false;
      types[i] = CandidateType.COMPLETION;
    }
    emoji_suggestion = null;
  }

  /** Replaces the word slots with append-only next-word candidates. */
  public void set_next_word_candidates(List<PredictionCandidate> candidates)
  {
    clear();
    for (int i = 0; i < candidates.size() && i < MAX_COUNT; i++)
    {
      suggestions[i] = candidates.get(i).text();
      types[i] = CandidateType.NEXT_WORD;
    }
    apply_candidate_case(suggestions, _candidate_case);
    count = count_suggestions(suggestions);
  }

  int query_suggestions(String typed_word, boolean sentence_start)
  {
    Cdict dict = _config.current_dictionary;
    boolean first_char_upper = Character.isUpperCase(typed_word.charAt(0));
    String word = apply_substitutions(typed_word);
    for (int i = 0; i < MAX_COUNT; i++)
    {
      suggestions[i] = null;
      personal_suggestions[i] = false;
      types[i] = CandidateType.COMPLETION;
    }
    int i = 0;
    if (dict != null)
    {
      Cdict.Result r_exact = dict.find(word);
      if (r_exact.found)
      {
        String result = dict.word(r_exact.index);
        if (!already_in(suggestions, i, result))
          suggestions[i++] = result;
      }
      Cdict.Result r_for_suffixes = r_exact;
      if (should_lookup_alternate_case(r_exact.prefix_ptr))
      {
        Cdict.Result r_alt = dict.find(alternate_first_character(word));
        if (r_alt.found && i < MAX_COUNT)
        {
          String result = dict.word(r_alt.index);
          if (!already_in(suggestions, i, result))
            suggestions[i++] = result;
        }
        r_for_suffixes = r_alt;
      }
      int[] suffixes = dict.suffixes(r_for_suffixes, MAX_COUNT);
      // Disable distance search for small words
      int[] dist = (word.length() < 3 || i + 1 >= MAX_COUNT) ? NO_RESULTS :
        dict.distance(word, 1, MAX_COUNT);
      for (int j = 0; j < MAX_COUNT && i < MAX_COUNT; j++)
      {
        if (suffixes.length > j)
        {
          String result = dict.word(suffixes[j]);
          if (!already_in(suggestions, i, result))
            suggestions[i++] = result;
        }
        if (dist.length > j && i < MAX_COUNT)
        {
          String result = dict.word(dist[j]);
          if (!already_in(suggestions, i, result))
            suggestions[i++] = result;
        }
      }
    }
    if (_config.user_dictionary_enabled && UserDictionary.instance() != null)
      prepend_personal_candidates(suggestions, personal_suggestions,
          UserDictionary.instance().find_prefix(word, 2));
    boolean capitalize = first_char_upper
      || (sentence_start && _config.capitalize_suggestions_at_sentence_start);
    if (capitalize)
      capitalize_results(suggestions);
    place_typed_word_last(suggestions, personal_suggestions, types, capitalize
        ? Utils.capitalize_string(typed_word) : typed_word);
    emoji_suggestion = query_emoji(word); // word with substitutions applied
    apply_candidate_case(suggestions, _candidate_case);
    count = count_suggestions(suggestions);
    return count;
  }

  static void prepend_personal_candidates(String[] candidates, boolean[] personal_candidates,
      String[] personal)
  {
    String[] merged = new String[candidates.length];
    boolean[] merged_personal = new boolean[candidates.length];
    int count = 0;
    for (int i = 0; i < personal.length && i < 2 && count < merged.length; i++)
      if (!already_in(candidates, candidates.length, personal[i])
          && !already_in(merged, count, personal[i]))
      {
        merged[count] = personal[i];
        merged_personal[count++] = true;
      }
    for (int i = 0; i < candidates.length && count < merged.length; i++)
      if (candidates[i] != null && !already_in(merged, count, candidates[i]))
        merged[count++] = candidates[i];
    System.arraycopy(merged, 0, candidates, 0, candidates.length);
    System.arraycopy(merged_personal, 0, personal_candidates, 0, candidates.length);
  }

  static void capitalize_results(String[] candidates)
  {
    for (int i = 0; i < candidates.length; i++)
      if (candidates[i] != null)
        candidates[i] = Utils.capitalize_string(candidates[i]);
  }

  public static void apply_candidate_case(String[] candidates,
      CandidateCase candidateCase)
  {
    if (candidateCase == CandidateCase.DEFAULT)
      return;
    for (int i = 0; i < candidates.length; i++)
    {
      String candidate = candidates[i];
      if (candidate == null)
        continue;
      if (candidateCase == CandidateCase.UPPER)
        candidates[i] = candidate.toUpperCase(Locale.ROOT);
      else if (candidate.length() > 0)
      {
        int first_end = candidate.offsetByCodePoints(0, 1);
        candidates[i] = candidate.substring(0, first_end).toUpperCase(Locale.ROOT)
          + candidate.substring(first_end).toLowerCase(Locale.ROOT);
      }
    }
  }

  static boolean already_in(String[] candidates, int count, String word)
  {
    for (int i = 0; i < count; i++)
      if (candidates[i] != null && candidates[i].equalsIgnoreCase(word))
        return true;
    return false;
  }

  static void place_typed_word_last(String[] candidates, String typed_word)
  {
    place_typed_word_last(candidates, null, typed_word);
  }

  static void place_typed_word_last(String[] candidates, boolean[] personal_candidates,
      String typed_word)
  {
    place_typed_word_last(candidates, personal_candidates, null, typed_word);
  }

  static void place_typed_word_last(String[] candidates, boolean[] personal_candidates,
      CandidateType[] candidate_types, String typed_word)
  {
    int matching_index = -1;
    int candidate_count = 0;
    for (int i = 0; i < candidates.length; i++)
    {
      if (candidates[i] != null)
      {
        candidate_count++;
        if (candidates[i].equalsIgnoreCase(typed_word))
          matching_index = i;
      }
    }
    if (matching_index == -1 && candidate_count < 2)
      return;
    if (matching_index >= 0)
    {
      String matching_word = candidates[matching_index];
      boolean matching_personal = personal_candidates != null
        && personal_candidates[matching_index];
      CandidateType matching_type = candidate_types == null ? null
        : candidate_types[matching_index];
      int last_index = candidate_count - 1;
      for (int i = matching_index; i < last_index; i++)
      {
        candidates[i] = candidates[i + 1];
        if (personal_candidates != null)
          personal_candidates[i] = personal_candidates[i + 1];
        if (candidate_types != null)
          candidate_types[i] = candidate_types[i + 1];
      }
      candidates[last_index] = matching_word;
      if (personal_candidates != null)
        personal_candidates[last_index] = matching_personal;
      if (candidate_types != null)
        candidate_types[last_index] = matching_type;
      return;
    }
    int last_index = Math.min(candidate_count, candidates.length - 1);
    candidates[last_index] = typed_word;
    if (personal_candidates != null)
      personal_candidates[last_index] = false;
    if (candidate_types != null)
      candidate_types[last_index] = CandidateType.COMPLETION;
  }

  public static String alternate_first_character(String word)
  {
    int first_char_end = word.offsetByCodePoints(0, 1);
    String first_char = word.substring(0, first_char_end);
    String rest = word.substring(first_char_end);
    return Character.isUpperCase(word.codePointAt(0))
      ? first_char.toLowerCase(Locale.getDefault()) + rest
      : Utils.capitalize_string(word);
  }

  static boolean should_lookup_alternate_case(long prefix_ptr)
  {
    return prefix_ptr == 0;
  }

  static int count_suggestions(String[] candidates)
  {
    int count = 0;
    for (int i = 0; i < candidates.length && candidates[i] != null; i++)
      count++;
    return count;
  }

  /** Removes a personal candidate from the active model and refreshes its view. */
  public boolean remove_personal_candidate(String candidate)
  {
    for (int i = 0; i < count; i++)
      if (personal_suggestions[i] && types[i] == CandidateType.COMPLETION
          && suggestions[i].equals(candidate))
      {
        for (int j = i; j < MAX_COUNT - 1; j++)
        {
          suggestions[j] = suggestions[j + 1];
          personal_suggestions[j] = personal_suggestions[j + 1];
          types[j] = types[j + 1];
        }
        suggestions[MAX_COUNT - 1] = null;
        personal_suggestions[MAX_COUNT - 1] = false;
        types[MAX_COUNT - 1] = CandidateType.COMPLETION;
        count = count_suggestions(suggestions);
        _callback.set_suggestions(this);
        return true;
      }
    return false;
  }

  String query_emoji(String word)
  {
    Cdict dict = _config.emoji_dictionary;
    // Disable emoji suggestion for short words
    if (dict == null || word.length() < 3)
      return null;
    Cdict.Result r = dict.find(word);
    if (r.found)
      return dict.word(r.index);
    int[] s = dict.suffixes(r, 1);
    if (s.length > 0)
      return dict.word(s[0]);
    return null;
  }

  /** Apply the same substitutions that were used when building the
      dictionaries to find word aliases. This catches missing diacritics for
      example. */
  String apply_substitutions(String w)
  {
    StringBuilder b = new StringBuilder(w);
    int len = w.length();
    for (int i = 0; i < len; i++)
    {
      char r =
        ComposeKey.transform_char(ComposeKeyData.substitutions, b.charAt(i));
      if (r != 0) b.setCharAt(i, r);
    }
    return b.toString();
  }

  static final int[] NO_RESULTS = new int[0];

  public static interface Callback
  {
    public void set_suggestions(Suggestions suggestions);
  }
}
