package juloo.keyboard2.suggestions;

import java.util.Arrays;
import java.util.List;
import juloo.cdict.Cdict;
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

  public Suggestions(Callback c, Config conf)
  {
    _callback = c;
    _config = conf;
  }

  public void currently_typed_word(String word, boolean sentence_start)
  {
    Cdict dict = _config.current_dictionary;
    if (word.length() < 2 || dict == null)
    {
      set_suggestions(NO_SUGGESTIONS);
    }
    else
    {
      String[] dst = new String[3];
      boolean effective_sentence_start = sentence_start
        && _config.capitalize_suggestions_at_sentence_start;
      query_suggestions(dict, word, dst, 3, effective_sentence_start);
      set_suggestions(Arrays.asList(dst));
    }
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
    int[] dist = (word.length() < 3 || i + 1 >= max_count) ? NO_RESULTS :
      dict.distance(word, 1, max_count);
    for (int j = 0; j < max_count && i < max_count; j++)
    {
      if (suffixes.length > j)
        dst[i++] = dict.word(suffixes[j]);
      if (dist.length > j && i < max_count)
        dst[i++] = dict.word(dist[j]);
    }
    // Capitalize the full dst array when user typed a capital or cursor is at sentence start
    if (first_char_upper || sentence_start)
      capitalize_results(dst);
    return i;
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
