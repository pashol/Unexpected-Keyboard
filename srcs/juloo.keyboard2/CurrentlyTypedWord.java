package juloo.keyboard2;

import android.os.Build.VERSION;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.SurroundingText;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Keep track of the word being typed. This also tracks whether the selection
    is empty. */
public final class CurrentlyTypedWord
{
  InputConnection _ic = null;
  Handler _handler;
  Callback _callback;

  /** The currently typed word. */
  StringBuilder _w = new StringBuilder();
  /** This can be disabled if the editor doesn't support looking at the text
      before the cursor. */
  boolean _enabled = false;
  /** The current word is empty while the selection is ongoing. */
  boolean _has_selection = false;
  /** Used to avoid concurrent refreshes in [delayed_refresh()]. */
  boolean _refresh_pending = false;

  /** The estimated cursor position in code points. Used to avoid expensive IPC
      calls when the typed word can be estimated locally with [typed]. When the
      cursor position gets out of sync, the text before the cursor is queried
      again to the editor. */
  int _cursor;
  /** The cursor position within the current word relative to the end of the
      word in chars. Equal to [0] when the cursor is at the end of the word. */
  int _w_cursor;
  String _text_before_cursor = "";
  /** Whether [_text_before_cursor] came from a successful editor query. */
  boolean _context_known = false;
  /** Whether the editor confirmed that there is no text after the cursor. */
  boolean _cursor_at_text_end = false;
  boolean _sentence_start = false;

  static final int SENTENCE_CONTEXT_LENGTH = 100;
  static final int MAX_RELIABLE_WORD_LENGTH = SENTENCE_CONTEXT_LENGTH - 1;

  public CurrentlyTypedWord(Handler h, Callback cb)
  {
    _handler = h;
    _callback = cb;
  }

  public String get()
  {
    return _w.toString();
  }

  public boolean is_selection_not_empty()
  {
    return _has_selection;
  }

  /** The cursor position relative to the end of the word. */
  public int cursor_relative()
  {
    return _w_cursor;
  }

  public boolean sentence_start()
  {
    return _sentence_start;
  }

  public void started(Config conf, InputConnection ic)
  {
    _ic = ic;
    _enabled = true;
    EditorConfig e = conf.editor_config;
    _has_selection = e.initial_sel_start != e.initial_sel_end;
    _cursor = e.initial_sel_start;
    _w_cursor = 0;
    if (!_has_selection)
    {
      CharSequence initial_text_before_cursor = e.initial_text_before_cursor;
      if (initial_text_before_cursor == null && VERSION.SDK_INT < 30 && ic != null)
        initial_text_before_cursor = ic.getTextBeforeCursor(SENTENCE_CONTEXT_LENGTH, 0);
      set_current_word(initial_text_before_cursor, false);
      _w_cursor = (e.initial_text_after_cursor == null) ? 0 :
        -append_chars(e.initial_text_after_cursor); 
      _cursor_at_text_end = e.initial_text_after_cursor == null
        ? text_after_cursor_is_empty()
        : e.initial_text_after_cursor.length() == 0;
      if (_callback != null)
        callback();
    }
  }

  public void typed(String s)
  {
    if (!_enabled)
      return;
    _has_selection = false;
    type_chars(s);
    callback();
  }

  public void selection_updated(int oldSelStart, int newSelStart, int newSelEnd)
  {
    // Avoid the expensive [refresh_current_word] call when [typed] was called
    // before.
    if (!_enabled)
      return;
    boolean new_has_sel = newSelStart != newSelEnd;
    if (new_has_sel || _has_selection) // Selection was on or is now on.
    {
      _cursor = newSelStart;
      _has_selection = new_has_sel;
      refresh_current_word();
    }
    else if (newSelStart != _cursor)
    {
      _cursor_at_text_end = false;
      _cursor = newSelStart;
      _w_cursor += newSelStart - oldSelStart;
      if (_w_cursor < -_w.length() || _w_cursor > 0)
        refresh_current_word();
      else
        callback();
    }
  }

  public void event_sent(int code, int meta)
  {
    if (!_enabled)
      return;
    switch (code)
    {
      case KeyEvent.KEYCODE_DEL:
        if (meta == 0)
          remove_surrounding_text(1, 0);
        else
          delayed_refresh();
        break;
      default:
        delayed_refresh();
        break;
    }
  }

  public void remove_surrounding_text(int remove_before, int remove_after)
  {
    if (!_enabled)
      return;
    int len = _w.length();
    int c = len + _w_cursor;
    _w.delete(Math.max(c - remove_before, 0), Math.min(c + remove_after, len));
    _text_before_cursor = _text_before_cursor.substring(0,
        Math.max(_text_before_cursor.length() - remove_before, 0));
    _cursor -= remove_before;
    _w_cursor -= Math.min(remove_after, 0);
    update_sentence_start();
    callback();
  }

  void callback()
  {
    String w = _w.toString();
    List<String> preceding_words = preceding_words_for_next_word();
    _callback.currently_typed_word(w, _sentence_start, preceding_words);
  }

  /** Estimate the currently typed word after [chars] has been typed. */
  void type_chars(CharSequence s, int start, int end)
  {
    int insert_start = 0;
    // Iterate over code points as that's the unit of [_cursor].
    for (int i = start; i < end;)
    {
      int c = Character.codePointAt(s, i);
      i += Character.charCount(c);
      _cursor++;
      // [i >= end] might happen when the cursor is in the middle of a
      // surrogate pair
      if (!is_word_char(c) && i <= end)
        insert_start = i;
    }
    if (insert_start > 0)
      _w.setLength(0);
    _w.insert(Math.max(_w.length() + _w_cursor, 0), s, insert_start, end);
  }

  void type_chars(CharSequence s)
  {
    type_chars(s, 0, s.length());
    _text_before_cursor += s;
    update_sentence_start();
  }

  /** Append chars to the current word without moving the cursor. Return the
      number of characters that were added in the current word. */
  int append_chars(CharSequence s, int start, int end)
  {
    int i = start;
    while (i < end)
    {
      int c = Character.codePointAt(s, i);
      if (!is_word_char(c))
        break;
      _w.appendCodePoint(c);
      i += Character.charCount(c);
    }
    return i - start;
  }

  int append_chars(CharSequence s)
  {
    return append_chars(s, 0, s.length());
  }

  /** Refresh the current word by immediately querying the editor. */
  void refresh_current_word()
  {
    Logs.debug("Refresh current word");
    _refresh_pending = false;
    _w_cursor = 0;
    if (_has_selection)
      set_current_word("");
    else if (VERSION.SDK_INT >= 31)
      set_current_word(_ic.getSurroundingText(SENTENCE_CONTEXT_LENGTH, 20, 0));
    else
      set_current_word(_ic.getTextBeforeCursor(SENTENCE_CONTEXT_LENGTH, 0),
          text_after_cursor_is_empty(), true);
  }

  /** Returns true only when the editor confirms there is no following text. */
  boolean text_after_cursor_is_empty()
  {
    if (_ic == null)
      return false;
    CharSequence text = _ic.getTextAfterCursor(1, 0);
    return text != null && text.length() == 0;
  }

  /** Refresh the current word by immediately querying the editor. */
  void set_current_word(CharSequence text_before_cursor)
  {
    set_current_word(text_before_cursor, true);
  }

  void set_current_word(CharSequence text_before_cursor, boolean notify)
  {
    set_current_word(text_before_cursor, false, notify);
  }

  void set_current_word(CharSequence text_before_cursor, boolean cursor_at_text_end,
      boolean notify)
  {
    _w.setLength(0);
    _text_before_cursor = "";
    _cursor_at_text_end = cursor_at_text_end;
    if (text_before_cursor == null)
    {
      _context_known = false;
      _sentence_start = false;
      if (notify && _callback != null)
        callback();
      return;
    }
    _context_known = true;
    int saved_cursor = _cursor;
    type_chars(text_before_cursor.toString());
    _cursor = saved_cursor;
    if (notify)
      callback();
  }

  /** Like above but take the text after the cursor into account. */
  void set_current_word(SurroundingText st)
  {
    _w.setLength(0);
    _text_before_cursor = "";
    _cursor_at_text_end = false;
    if (st == null)
    {
      _context_known = false;
      _sentence_start = false;
      if (_callback != null)
        callback();
      return;
    }
    _context_known = true;
    int saved_cursor = _cursor;
    int st_sel = st.getSelectionStart();
    CharSequence st_text = st.getText();
    type_chars(st_text, 0, st_sel);
    _w_cursor = -append_chars(st_text, st_sel, st_text.length());
    _text_before_cursor = st_text.subSequence(0, st_sel).toString();
    _cursor_at_text_end = st_sel == st_text.length();
    update_sentence_start();
    _cursor = saved_cursor;
    callback();
  }

  /** Wait some time to let the editor finishes reacting to changes and call
      [refresh_current_word]. */
  void delayed_refresh()
  {
    _refresh_pending = true;
    _handler.postDelayed(delayed_refresh_run, 50);
  }

  Runnable delayed_refresh_run = new Runnable()
  {
    public void run()
    {
      if (_refresh_pending)
        refresh_current_word();
    }
  };

  /** A word is the longest consecutive sequence for which [is_word_char]
      returns [true]. */
  public static boolean is_word_char(int c)
  {
    return Character.isLetterOrDigit(c) || (c == '\'');
  }

  /** Return up to three complete words before an empty composition. */
  List<String> preceding_words_for_next_word()
  {
    if (_has_selection || !_cursor_at_text_end)
      return Collections.emptyList();
    return preceding_words_for_next_word(_text_before_cursor, _context_known,
        _w.toString());
  }

  /** Return up to three complete words before an empty composition. */
  static List<String> preceding_words_for_next_word(String context,
      boolean contextKnown, String composingWord)
  {
    if (!contextKnown || composingWord.length() != 0 || context.length() == 0
        || !Character.isWhitespace(context.codePointBefore(context.length()))
        || (context.length() == SENTENCE_CONTEXT_LENGTH
            && (is_word_char(context.codePointAt(0))
                || Character.isLowSurrogate(context.charAt(0)))))
      return Collections.emptyList();
    ArrayList<String> words = new ArrayList<>();
    int i = context.length();
    while (i > 0 && words.size() < 3)
    {
      while (i > 0 && !is_word_char(context.codePointBefore(i)))
      {
        int c = context.codePointBefore(i);
        i -= Character.charCount(c);
      }
      int word_end = i;
      while (i > 0 && is_word_char(context.codePointBefore(i)))
      {
        int c = context.codePointBefore(i);
        i -= Character.charCount(c);
      }
      if (i != word_end)
        words.add(context.substring(i, word_end));
    }
    Collections.reverse(words);
    return words;
  }

  static boolean sentence_start_from_context(String text, int wordLength)
  {
    if (wordLength == 0 || wordLength > MAX_RELIABLE_WORD_LENGTH ||
        wordLength > text.length())
      return false;
    int i = Math.max(0, text.length() - wordLength);
    if (i == 0)
      return text.length() < SENTENCE_CONTEXT_LENGTH;
    if (!Character.isWhitespace(text.charAt(i - 1)))
      return false;
    boolean newline = false;
    while (i > 0 && Character.isWhitespace(text.charAt(i - 1)))
    {
      newline |= text.charAt(--i) == '\n';
    }
    if (i == 0)
      return text.length() < SENTENCE_CONTEXT_LENGTH;
    char c = text.charAt(i - 1);
    return newline || c == '.' || c == '!' || c == '?';
  }

  void update_sentence_start()
  {
    _sentence_start = _context_known && _w.length() > 0 &&
      sentence_start_from_context(_text_before_cursor,
          _w.length() + _w_cursor);
  }

  public static interface Callback
  {
    public void currently_typed_word(String word, boolean sentence_start);

    public default void currently_typed_word(String word, boolean sentence_start,
        List<String> preceding_words)
    {
      currently_typed_word(word, sentence_start);
    }
  }
}
