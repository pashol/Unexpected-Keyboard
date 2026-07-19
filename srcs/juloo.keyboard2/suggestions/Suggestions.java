package juloo.keyboard2.suggestions;

import android.text.InputType;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import juloo.cdict.Cdict;
import juloo.keyboard2.Config;
import juloo.keyboard2.ComposeKey;
import juloo.keyboard2.ComposeKeyData;
import juloo.keyboard2.prediction.ComposingContext;
import juloo.keyboard2.prediction.EditorPredictionPolicy;
import juloo.keyboard2.prediction.PredictionCandidate;
import juloo.keyboard2.prediction.PredictionEngine;
import juloo.keyboard2.prediction.PredictionEngineController;
import juloo.keyboard2.prediction.PredictionEngineFactory;
import juloo.keyboard2.prediction.PredictionRequest;

/** Keep track of the word being typed and provide suggestions for
    [CandidatesView]. */
public final class Suggestions
{
  Callback _callback;
  Config _config;
  boolean _enabled;
  final PredictionEngineController _controller;
  final PredictionEngineFactory _factory;
  final LanguageTagProvider _language_tag_provider;
  long _generation;
  AdaptedCandidates _adapted_candidates;

  /** Current suggestions. The best suggestion is at index [0]. */
  public String[] suggestions = new String[MAX_COUNT];
  /** Whether each suggestion slot was sourced from the personal dictionary. */
  public boolean[] personal_suggestions = new boolean[MAX_COUNT];
  /** Number of suggestions at the beginning of the [suggestions] array that
      are not [null]. */
  public int count = 0;
  public String emoji_suggestion = null;
  /** Number of suggestions in [suggestions]. */
  public static final int MAX_COUNT = 3;

  public Suggestions(Callback c, Config conf)
  {
    this(c, conf, PredictionEngineFactory.production());
  }

  Suggestions(Callback c, Config conf, PredictionEngineFactory factory)
  {
    _callback = c;
    _config = conf;
    _factory = factory;
    _language_tag_provider = () -> active_language_tag(conf);
    _controller = factory.create(conf,
        canonical_language_tag(_language_tag_provider.active_language_tag()),
        conf != null && conf.experimental_prediction_engine);
    _adapted_candidates = PredictionCandidateAdapter.adapt(
        Collections.emptyList(), _generation, false);
  }

  public Suggestions(Callback c, Config conf, PredictionEngine engine)
  {
    this(c, conf, engine, () -> active_language_tag(conf));
  }

  public Suggestions(Callback c, Config conf,
      PredictionEngineController controller)
  {
    this(c, conf, controller, () -> active_language_tag(conf));
  }

  Suggestions(Callback c, Config conf, PredictionEngine engine,
      LanguageTagProvider languageTagProvider)
  {
    this(c, conf, new PredictionEngineController(engine, null, false),
        languageTagProvider);
  }

  Suggestions(Callback c, Config conf, PredictionEngineController controller,
      LanguageTagProvider languageTagProvider)
  {
    _callback = c;
    _config = conf;
    _controller = controller;
    _factory = null;
    _language_tag_provider = languageTagProvider;
    _adapted_candidates = PredictionCandidateAdapter.adapt(
        Collections.emptyList(), _generation);
  }

  public void started()
  {
    _controller.resetSession();
    _enabled = _config.editor_config.should_show_candidates_view;
    clear();
  }

  public void finished()
  {
    _controller.resetSession();
  }

  public void close()
  {
    _controller.close();
  }

  public void rebuild_prediction_engine()
  {
    if (_factory == null)
      return;
    _factory.rebuild(_controller, _config,
        canonical_language_tag(_language_tag_provider.active_language_tag()),
        _config.experimental_prediction_engine);
  }

  public void currently_typed_word(ComposingContext context)
  {
    if (!_enabled)
      return;
    String word = context.composingText;
    if (word.length() < 2 || (_config != null && _config.current_dictionary == null
          && (!_config.user_dictionary_enabled || UserDictionary.instance() == null)))
      clear();
    else
      query_suggestions(context);
    _callback.set_suggestions(this);
  }

  void clear()
  {
    count = 0;
    for (int i = 0; i < MAX_COUNT; i++)
    {
      suggestions[i] = null;
      personal_suggestions[i] = false;
    }
    emoji_suggestion = null;
    _adapted_candidates = PredictionCandidateAdapter.adapt(
        Collections.emptyList(), _generation);
  }

  int query_suggestions(ComposingContext context)
  {
    long generation = ++_generation;
    PredictionRequest request = new PredictionRequest(
        context.composingText, context.composingCursorCodePoint,
        context.precedingWords, context.sentenceStart,
        canonical_language_tag(_language_tag_provider.active_language_tag()),
         MAX_COUNT, generation, editor_prediction_policy());
    List<PredictionCandidate> result = _controller.predict(request);
    _adapted_candidates = PredictionCandidateAdapter.adapt(
        result, generation, _controller.isGenerationExperimental(generation));
    apply_adapted_candidates();
    emoji_suggestion = _config == null
      ? null : query_emoji(apply_substitutions(context.composingText));
    return count;
  }

  public boolean is_current_generation_experimental()
  {
    return _adapted_candidates.isExperimental();
  }

  public boolean can_auto_complete_current_candidate()
  {
    return !_adapted_candidates.isExperimental();
  }

  private EditorPredictionPolicy editor_prediction_policy()
  {
    if (_config != null && _config.editor_config != null
        && _config.editor_config.prediction_policy != null)
      return _config.editor_config.prediction_policy;
    return EditorPredictionPolicy.from(
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL, 0, null);
  }

  static String canonical_language_tag(String languageTag)
  {
    return languageTag == null || languageTag.length() == 0
      ? "und" : Locale.forLanguageTag(languageTag).toLanguageTag();
  }

  private static String active_language_tag(Config config)
  {
    return config != null && config.device_locales != null
      && config.device_locales.default_ != null
      ? config.device_locales.default_.lang_tag : null;
  }

  private void apply_adapted_candidates()
  {
    String[] adaptedSuggestions = _adapted_candidates.getSuggestions();
    boolean[] adaptedPersonal = _adapted_candidates.getPersonalSuggestions();
    System.arraycopy(adaptedSuggestions, 0, suggestions, 0, MAX_COUNT);
    System.arraycopy(adaptedPersonal, 0, personal_suggestions, 0, MAX_COUNT);
    count = _adapted_candidates.getCount();
  }

  /** Removes a personal candidate from the active model and refreshes its view. */
  public boolean remove_personal_candidate(String candidate)
  {
    for (int i = 0; i < count; i++)
      if (personal_suggestions[i] && suggestions[i].equals(candidate))
      {
        _adapted_candidates = PredictionCandidateAdapter.removePersonalCandidate(
            _adapted_candidates, candidate);
        apply_adapted_candidates();
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

  public static interface Callback
  {
    public void set_suggestions(Suggestions suggestions);
  }

  interface LanguageTagProvider
  {
    String active_language_tag();
  }
}
