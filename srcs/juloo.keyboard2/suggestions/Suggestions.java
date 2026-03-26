package juloo.keyboard2.suggestions;

import java.util.Arrays;
import java.util.List;
import juloo.cdict.Cdict;
import juloo.keyboard2.UserDictionary;
import juloo.keyboard2.dict.Dictionaries;
import juloo.keyboard2.Config;
import juloo.keyboard2.Utils;

/** Keep track of the word being typed and provide suggestions for
    [CandidatesView]. */
public final class Suggestions
{
  Callback _callback;
  Config _config;

  /** The suggestion displayed at the center of the candidates view and entered
      by the space bar. */
  public String best_suggestion = null;

  private String _last_word = null;
  private final LanguageMomentum _momentum = new LanguageMomentum(-1);

  public Suggestions(Callback c, Config conf)
  {
    _callback = c;
    _config = conf;
  }

  /** Reset word tracking and momentum. Call when starting a new text field. */
  public void reset(int default_dict_index)
  {
    _last_word = null;
    _momentum.reset(default_dict_index);
    set_suggestions(NO_SUGGESTIONS);
  }

  public void currently_typed_word(String word, boolean sentence_start)
  {
    // Detect word boundary (empty word = previous word was completed)
    if (word.length() < 2 && _last_word != null)
    {
      _record_momentum(_last_word);
      _last_word = null;
    }
    else if (word.length() >= 2)
    {
      _last_word = word;
    }

    Cdict[] dicts = _config.current_dictionaries;
    if (word.length() < 2 || (dicts == null && !_config.user_dictionary_enabled))
    {
      set_suggestions(NO_SUGGESTIONS);
      return;
    }
    boolean effective_sentence_start = sentence_start
      && _config.capitalize_suggestions_at_sentence_start;
    boolean first_char_upper = word.length() > 0 && Character.isUpperCase(word.charAt(0));
    boolean should_capitalize = effective_sentence_start || first_char_upper;
    String[] dst = new String[3];
    int i = 0;
    // Prepend personal dictionary matches (cap at 2 so Cdict always gets ≥1 slot)
    if (_config.user_dictionary_enabled)
    {
      for (String m : UserDictionary.getInstance().find_prefix(word, 2))
      {
        if (i >= 2) break;
        if (should_capitalize) m = juloo.keyboard2.Utils.capitalize_string(m);
        dst[i++] = m;
      }
    }
    // Fill remaining slots from Cdict
    if (dicts != null && i < 3)
    {
      if (dicts.length == 1)
      {
        // Single dict: use the original path (no momentum overhead)
        String[] cdictDst = new String[3 - i];
        query_suggestions(dicts[0], word, cdictDst, 3 - i, effective_sentence_start);
        for (String s : cdictDst)
          if (s != null && i < 3) dst[i++] = s;
      }
      else
      {
        i = query_multi_dict(dicts, word, dst, i, 3, effective_sentence_start);
      }
    }
    // Ensure the typed word is at slot 0 (center/autocomplete).
    // If it's already somewhere in dst (e.g. Cdict found it but user-dict
    // entries pushed it to slot 2), rotate it to the front rather than just
    // checking already_in and skipping (which left it in the wrong slot).
    // Exception: if the word is NOT in any dictionary (not found in dst) AND
    // there is exactly 1 suggestion, keep that suggestion for unambiguous
    // autocomplete (e.g. "Grosswan" -> "Grosswangen").
    String display_word = should_capitalize
      ? juloo.keyboard2.Utils.capitalize_string(word) : word;
    boolean at_slot_0 = dst[0] != null && dst[0].equalsIgnoreCase(display_word);
    if (!at_slot_0)
    {
      int found_at = -1;
      for (int k = 1; k < dst.length; k++)
        if (dst[k] != null && dst[k].equalsIgnoreCase(display_word)) { found_at = k; break; }
      int suggestion_count = 0;
      for (String s : dst) if (s != null) suggestion_count++;
      // Inject/promote when: word is already in dst (a dictionary found it, just
      // in the wrong slot), OR there are 2+ suggestions (ambiguous).
      if (found_at > 0 || suggestion_count >= 2)
      {
        if (found_at > 0)
        {
          // Rotate: slide entries before found_at one slot to the right
          for (int k = found_at; k > 0; k--)
            dst[k] = dst[k - 1];
        }
        else
        {
          // Not present: push everything right (last entry is dropped)
          dst[2] = dst[1];
          dst[1] = dst[0];
        }
        dst[0] = display_word;
      }
    }
    set_suggestions(Arrays.asList(dst));
  }

  /** Returns quality of [dict]'s match for [word]: 2=exact, 1=prefix/trie-hit, 0=no match. */
  static int dict_quality(Cdict dict, String word)
  {
    Cdict.Result r = dict.find(word);
    if (r.found) return 2;
    String alt = Character.isUpperCase(word.charAt(0)) ? word.toLowerCase()
                 : Utils.capitalize_string(word);
    Cdict.Result r_alt = dict.find(alt);
    if (r_alt.found) return 2;
    if (r.prefix_ptr != 0 || r_alt.prefix_ptr != 0) return 1;
    return 0;
  }

  private void _record_momentum(String word)
  {
    Cdict[] dicts = _config.current_dictionaries;
    if (dicts == null || dicts.length <= 1) return;
    int best = -1;
    int best_quality = 0;
    for (int i = 0; i < dicts.length; i++)
    {
      if (dicts[i] == null) continue;
      int q = dict_quality(dicts[i], word);
      if (q > best_quality) { best_quality = q; best = i; }
    }
    _momentum.record(best); // -1 if no dict matched
  }

  /** Query all [dicts] for [word], ranking by quality+momentum. Writes results into
      [dst] starting at [start], up to [max] slots. Returns updated index. */
  int query_multi_dict(Cdict[] dicts, String word, String[] dst, int start, int max,
      boolean sentence_start)
  {
    // Find the dict with the best quality + momentum score
    int best = -1;
    float best_score = -1f;
    for (int i = 0; i < dicts.length; i++)
    {
      if (dicts[i] == null) continue;
      float score = dict_quality(dicts[i], word) * 2f + _momentum.score(i);
      if (score > best_score) { best_score = score; best = i; }
    }
    int i = start;
    // Best dict first
    if (best >= 0 && i < max)
    {
      String[] sub = new String[max - i];
      int n = query_suggestions(dicts[best], word, sub, max - i, sentence_start);
      for (int k = 0; k < n && i < max; k++)
        if (sub[k] != null) dst[i++] = sub[k];
    }
    // Fill remaining slots from other dicts (skip duplicates)
    for (int d = 0; d < dicts.length && i < max; d++)
    {
      if (d == best || dicts[d] == null) continue;
      String[] sub = new String[max - i];
      int n = query_suggestions(dicts[d], word, sub, max - i, sentence_start);
      for (int k = 0; k < n && i < max; k++)
        if (sub[k] != null && !already_in(dst, i, sub[k])) dst[i++] = sub[k];
    }
    return i;
  }

  int query_suggestions(Cdict dict, String word, String[] dst, int max_count, boolean sentence_start)
  {
    Cdict.Result r_exact = dict.find(word);
    boolean first_char_upper = Character.isUpperCase(word.charAt(0));
    // Also try the opposite-first-char form so that:
    //   - German nouns (stored as "Erde") are found when typing "erde"
    //   - Lowercase forms ("the") are found when typing "The"
    String alt_word = first_char_upper
      ? word.toLowerCase()
      : juloo.keyboard2.Utils.capitalize_string(word);
    Cdict.Result r_alt = dict.find(alt_word);

    int i = 0;
    // Write exact match — but skip if r_alt produces the same word (prevents duplicates)
    if (r_exact.found)
    {
      if (!r_alt.found || !word.equalsIgnoreCase(dict.word(r_alt.index)))
        dst[i++] = word;
    }
    // Write alt match as stored in dictionary (preserves correct casing, e.g. "Erde")
    if (r_alt.found && i < max_count)
      dst[i++] = dict.word(r_alt.index);

    // Suffix traversal: prefer r_exact when it has a valid prefix node (covers lowercase
    // completions). Fall back to r_alt only when r_exact has no usable prefix (e.g. "erde"
    // is not a prefix of any lowercase entry, but "Erde" subtree has "Erdbeere" etc.)
    Cdict.Result r_for_suffixes = (r_exact.found || r_exact.prefix_ptr != 0) ? r_exact : r_alt;
    int[] suffixes = dict.suffixes(r_for_suffixes, max_count);
    // Disable distance search for small words
    int[] dist = (word.length() < 3 || i >= max_count) ? NO_RESULTS :
      dict.distance(word, 1, max_count);
    for (int j = 0; j < max_count && i < max_count; j++)
    {
      if (suffixes.length > j)
      {
        String w = dict.word(suffixes[j]);
        if (!already_in(dst, i, w))
          dst[i++] = w;
      }
      if (dist.length > j && i < max_count)
      {
        String w = dict.word(dist[j]);
        if (!already_in(dst, i, w))
          dst[i++] = w;
      }
    }
    // Distance-2 fallback: catches diacritic substitutions (e.g. "oppis" → "öppis").
    // ö is 2 bytes in UTF-8, so its byte-level edit distance from 'o' is 2, not 1.
    // Only fired for pure-ASCII input (user omitted an accent) and words ≥ 5 chars
    // to avoid noisy candidates on short inputs.
    if (word.length() >= 5 && i < max_count && is_pure_ascii(word))
    {
      int remaining = max_count - i;
      int[] dist2 = dict.distance(word, 2, remaining);
      for (int j = 0; j < dist2.length && i < max_count; j++)
      {
        String w = dict.word(dist2[j]);
        if (!already_in(dst, i, w))
          dst[i++] = w;
      }
    }
    // Capitalize the full dst array when user typed a capital or cursor is at sentence start
    if (first_char_upper || sentence_start)
      capitalize_results(dst);
    return i;
  }

  static boolean is_pure_ascii(String word)
  {
    for (int k = 0; k < word.length(); k++)
      if (word.charAt(k) >= 0x80) return false;
    return true;
  }

  static boolean already_in(String[] dst, int count, String word)
  {
    for (int k = 0; k < count; k++)
      if (dst[k] != null && dst[k].equalsIgnoreCase(word))
        return true;
    return false;
  }

  void capitalize_results(String[] rs)
  {
    for (int i = 0; i < rs.length; i++)
      if (rs[i] != null)
        rs[i] = juloo.keyboard2.Utils.capitalize_string(rs[i]);
  }

  void set_suggestions(List<String> ws)
  {
    _callback.set_suggestions(ws);
    best_suggestion = (ws.size() > 0) ? ws.get(0) : null;
  }

  static final List<String> NO_SUGGESTIONS = Arrays.asList();
  static final int[] NO_RESULTS = new int[0];

  public static interface Callback
  {
    public void set_suggestions(List<String> suggestions);
  }
}
