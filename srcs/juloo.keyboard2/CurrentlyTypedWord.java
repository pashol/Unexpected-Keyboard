package juloo.keyboard2;

import android.os.Handler;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
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

  /** Whether the cursor is at the start of a sentence (after . ! ? \n or at field start). */
  boolean _at_sentence_start = false;

  /** The estimated cursor position. Used to avoid expensive IPC calls when the
      typed word can be estimated locally with [typed]. When the cursor
      position gets out of sync, the text before the cursor is queried again to
      the editor. */
  int _cursor;

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

  public void started(Config conf, InputConnection ic)
  {
    _ic = ic;
    _enabled = true;
    EditorConfig e = conf.editor_config;
    _has_selection = e.initial_sel_start != e.initial_sel_end;
    _cursor = e.initial_sel_start;
    if (!_has_selection)
      set_current_word(e.initial_text_before_cursor);
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
    boolean new_has_sel = newSelStart != newSelEnd;
    if (!_enabled || (newSelStart == _cursor && new_has_sel == _has_selection))
      return;
    _has_selection = new_has_sel;
    _cursor = newSelStart;
    refresh_current_word();
  }

  public void event_sent(int code, int meta)
  {
    if (!_enabled)
      return;
    delayed_refresh();
  }

  void callback()
  {
    _callback.currently_typed_word(_w.toString());
  }

  /** Estimate the currently typed word after [chars] has been typed. */
  void type_chars(String s)
  {
    int len = s.length();
    for (int i = 0; i < len;)
    {
      int c = s.codePointAt(i);
      if (Character.isLetter(c))
        _w.appendCodePoint(c);
      else
        _w.setLength(0);
      _cursor++;
      i += Character.charCount(c);
    }
  }

  /** Refresh the current word by immediately querying the editor. */
  void refresh_current_word()
  {
    _refresh_pending = false;
    if (_has_selection)
      set_current_word("");
    else
      set_current_word(_ic.getTextBeforeCursor(30, 0));
  }

  /** Refresh the current word by immediately querying the editor. */
  void set_current_word(CharSequence text_before_cursor)
  {
    _w.setLength(0);
    _at_sentence_start = false;
    if (text_before_cursor == null)
      return;
    int saved_cursor = _cursor;
    String text = text_before_cursor.toString();
    type_chars(text);
    _cursor = saved_cursor;
    _at_sentence_start = sentence_start_from_context(text, _w.length());
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

  /** Pure helper — computes sentence-start from fetched text and current word length.
      Package-private for unit testing. */
  static boolean sentence_start_from_context(String text, int word_length)
  {
    if (word_length == 0 || word_length >= 30)
      return false;
    int prefix_len = text.length() - word_length;
    if (prefix_len < 0)
      return false;
    // Scan backwards past spaces to find the last non-space character
    int i = prefix_len - 1;
    while (i >= 0 && text.charAt(i) == ' ')
      i--;
    if (i < 0)
      return true; // Nothing before the word — field start or start of fetched window
    char c = text.charAt(i);
    return c == '.' || c == '!' || c == '?' || c == '\n';
  }

  public static interface Callback
  {
    public void currently_typed_word(String word);
  }
}
