package juloo.keyboard2;

import android.os.Build.VERSION;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.SurroundingText;
import java.util.Collections;
import java.util.List;
import juloo.keyboard2.prediction.ComposingContext;
import juloo.keyboard2.prediction.PrecedingContextExtractor;

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
      set_current_word(e.initial_text_before_cursor, false);
      _w_cursor = (e.initial_text_after_cursor == null) ? 0 :
        -append_chars(e.initial_text_after_cursor);
      if (_context_known)
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
      _cursor = newSelStart;
      int old_word_cursor = _w_cursor;
      int new_word_cursor = _w_cursor + newSelStart - oldSelStart;
      int new_word_position = _w.length() + new_word_cursor;
      if (new_word_cursor < -_w.length() || new_word_cursor > 0 ||
          !is_code_point_boundary(_w, new_word_position))
        refresh_current_word();
      else
      {
        _w_cursor = new_word_cursor;
        synchronize_text_before_cursor(old_word_cursor);
      }
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
    int removed_after = Math.min(Math.max(remove_after, 0), len - c);
    _w.delete(Math.max(c - remove_before, 0), Math.min(c + remove_after, len));
    _text_before_cursor = _text_before_cursor.substring(0,
        Math.max(_text_before_cursor.length() - remove_before, 0));
    _cursor -= remove_before;
    _w_cursor += removed_after - Math.min(remove_after, 0);
    update_sentence_start();
    callback();
  }

  void callback()
  {
    String w = _w.toString();
    int composingPrefixChars = _w.length() + _w_cursor;
    int cursorCodePoint = w.codePointCount(0, composingPrefixChars);
    List<String> precedingWords = Collections.emptyList();
    if (_context_known)
    {
      int boundedPrefixChars = Math.min(
          composingPrefixChars, _text_before_cursor.length());
      precedingWords = PrecedingContextExtractor.extract(
          _text_before_cursor, boundedPrefixChars);
    }
    _callback.currently_typed_word(new ComposingContext(
        w, cursorCodePoint, precedingWords, _sentence_start, _context_known));
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
    trim_text_before_cursor();
    update_sentence_start();
  }

  void trim_text_before_cursor()
  {
    if (_text_before_cursor.length() > SENTENCE_CONTEXT_LENGTH)
    {
      int start = _text_before_cursor.length() - SENTENCE_CONTEXT_LENGTH;
      if (Character.isLowSurrogate(_text_before_cursor.charAt(start)))
        start++;
      _text_before_cursor = _text_before_cursor.substring(start);
    }
  }

  void synchronize_text_before_cursor(int old_word_cursor)
  {
    if (!_context_known)
      return;
    int movement = _w_cursor - old_word_cursor;
    if (movement < 0)
    {
      _text_before_cursor = _text_before_cursor.substring(0,
          Math.max(0, _text_before_cursor.length() + movement));
    }
    else if (movement > 0)
    {
      int old_position = _w.length() + old_word_cursor;
      _text_before_cursor += _w.substring(old_position, old_position + movement);
      trim_text_before_cursor();
    }
  }

  static boolean is_code_point_boundary(CharSequence text, int position)
  {
    return position <= 0 || position >= text.length() ||
      !Character.isLowSurrogate(text.charAt(position)) ||
      !Character.isHighSurrogate(text.charAt(position - 1));
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
      set_current_word(_ic.getTextBeforeCursor(SENTENCE_CONTEXT_LENGTH, 0));
  }

  /** Refresh the current word by immediately querying the editor. */
  void set_current_word(CharSequence text_before_cursor)
  {
    set_current_word(text_before_cursor, true);
  }

  void set_current_word(CharSequence text_before_cursor, boolean notify)
  {
    _w.setLength(0);
    _text_before_cursor = "";
    if (text_before_cursor == null)
    {
      _context_known = false;
      _sentence_start = false;
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
    if (st == null)
    {
      _context_known = false;
      _sentence_start = false;
      return;
    }
    _context_known = true;
    int saved_cursor = _cursor;
    int st_sel = st.getSelectionStart();
    CharSequence st_text = st.getText();
    type_chars(st_text, 0, st_sel);
    _w_cursor = -append_chars(st_text, st_sel, st_text.length());
    _text_before_cursor = st_text.subSequence(0, st_sel).toString();
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
    public void currently_typed_word(ComposingContext context);
  }
}
