package juloo.keyboard2.suggestions;

import android.content.Context;
import android.os.Build.VERSION;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import juloo.keyboard2.Config;
import juloo.keyboard2.R;

public class CandidatesView extends LinearLayout
{
  static final int NUM_CANDIDATES = 4;

  /** Candidates currently visible. Entries can be [null] when there are less
      than [NUM_CANDIDATES] suggestions.
      - Entries at indexes [0] to [2] are word suggestions.
      - Entry at index [3] is the emoji suggestion. */
  String[] _items = new String[NUM_CANDIDATES];
  boolean[] _personal_items = new boolean[NUM_CANDIDATES];

  /** Text views showing the candidates in [_items]. Text views visibility is
      set to [GONE] when there are less than [NUM_CANDIDATES] suggestions. */
  TextView[] _item_views = new TextView[NUM_CANDIDATES];

  /** Message when no dictionary is installed. Visible when no candidates are
      shown. Might be [null]. */
  View _status_no_dict = null;

  public CandidatesView(Context context, AttributeSet attrs)
  {
    super(context, attrs);
  }

  @Override
  protected void onFinishInflate()
  {
    super.onFinishInflate();
    setup_item_view(0, R.id.candidates_middle);
    setup_item_view(1, R.id.candidates_right);
    setup_item_view(2, R.id.candidates_left);
    setup_item_view(3, R.id.candidates_emoji);
  }

  public void set_candidates(Suggestions s)
  {
    int s_count = s.count;
    for (int i = 0; i < Suggestions.MAX_COUNT; i++)
    {
      _items[i] = (i < s_count) ? s.suggestions[i] : null;
      _personal_items[i] = i < s_count && s.personal_suggestions[i];
    }
    _items[3] = s.emoji_suggestion;
    _personal_items[3] = false;
    // Hide the status message when showing candidates.
    if (s_count != 0 && _status_no_dict != null)
      _status_no_dict.setVisibility(View.GONE);
    for (int i = 0; i < _item_views.length; i++)
    {
      TextView v = _item_views[i];
      if (_items[i] != null)
      {
        v.setText(_items[i]);
        v.setVisibility(View.VISIBLE);
      }
      else
      {
        v.setVisibility(View.GONE);
      }
    }
  }

  void clear_candidates()
  {
    for (int i = 0; i < _item_views.length; i++)
    {
      _items[i] = null;
      _personal_items[i] = false;
      _item_views[i].setVisibility(View.GONE);
    }
  }

  public void refresh_config(Config config)
  {
    clear_candidates();
    // The status message indicates whether the dictionaries should be
    // installed.
    if (config.current_dictionary == null)
      inflate_status_no_dict(config);
    else if (_status_no_dict != null)
      _status_no_dict.setVisibility(View.GONE);
    set_sizes(config);
  }

  /** Set the height of the suggestion row and the text size. */
  void set_sizes(Config config)
  {
    // Make the candidates view about as high as a keyboard row.
    float row_height = config.keyboard_rows_height_pixels * (1 - config.key_vertical_margin);
    ViewGroup.MarginLayoutParams p =
      (ViewGroup.MarginLayoutParams)getLayoutParams();
    p.height = (int)row_height;
    setLayoutParams(p);
    // Match the size of labels on the keyboard.
    float text_size = row_height * config.characterSize * config.labelTextSize * 1.15f;
    for (int i = 0; i < NUM_CANDIDATES; i++)
    {
      TextView v = _item_views[i];
      // Set text size and enable auto size if supported.
      if (VERSION.SDK_INT < 26)
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, text_size);
      else
        v.setAutoSizeTextTypeUniformWithConfiguration(
            (int)(text_size / 2.), (int)text_size, 1, TypedValue.COMPLEX_UNIT_PX);
    }
  }

  /** Show or hide a status view and inflate it if needed. */
  View inflate_and_show(View v, boolean show, int layout_id)
  {
    if (!show)
    {
      if (v != null)
        v.setVisibility(View.GONE);
    }
    else
    {
      if (v == null)
      {
        v = View.inflate(getContext(), layout_id, null);
        addView(v);
      }
      v.setVisibility(View.VISIBLE);
    }
    return v;
  }

  void inflate_status_no_dict(Config config)
  {
    if (_status_no_dict == null)
    {
      _status_no_dict = View.inflate(getContext(),
          R.layout.candidates_status_no_dict, null);
      addView(_status_no_dict);
    }
    Locale current_locale = (config.device_locales.default_ != null) ?
      Locale.forLanguageTag(config.device_locales.default_.lang_tag) : null;
    TextView tv = _status_no_dict.findViewById(android.R.id.text1);
    if (tv != null && current_locale != null)
      tv.setText(getResources().getString(
            R.string.candidates_status_click_to_install,
            current_locale.getDisplayName()));
    _status_no_dict.setVisibility(View.VISIBLE);
  }

  private void setup_item_view(final int item_index, int item_id)
  {
    TextView v = (TextView)findViewById(item_id);
    v.setOnTouchListener(new View.OnTouchListener()
        {
          private boolean _removed;
          private String _long_press_candidate;
          private boolean _long_press_personal;
          private final Runnable _remove = new Runnable()
          {
            @Override
            public void run()
            {
              Config config = Config.globalConfig();
              UserDictionary dictionary = UserDictionary.instance();
              if (matches_long_press_candidate(_long_press_candidate,
                    _long_press_personal, _items[item_index], _personal_items[item_index])
                  && config != null && can_remove_personal_candidate(
                    config.user_dictionary_enabled, item_index, _long_press_personal)
                  && dictionary != null
                  && remove_personal_candidate(dictionary, _long_press_candidate))
              {
                config.handler.personal_candidate_removed(_long_press_candidate);
                _removed = true;
              }
            }
          };

          @Override
          public boolean onTouch(View view, MotionEvent event)
          {
            switch (event.getActionMasked())
            {
              case MotionEvent.ACTION_DOWN:
                _removed = false;
                _long_press_candidate = _items[item_index];
                _long_press_personal = _personal_items[item_index];
                Config config = Config.globalConfig();
                if (config != null && can_remove_personal_candidate(
                      config.user_dictionary_enabled, item_index,
                      _long_press_personal) && _long_press_candidate != null)
                  view.postDelayed(_remove, 600);
                break;
              case MotionEvent.ACTION_UP:
                view.removeCallbacks(_remove);
                return _removed;
              case MotionEvent.ACTION_CANCEL:
                view.removeCallbacks(_remove);
                break;
            }
            return false;
          }
        });
    v.setOnClickListener(new View.OnClickListener()
        {
          @Override
          public void onClick(View _v)
          {
            String it = _items[item_index];
            if (it != null)
              Config.globalConfig().handler.suggestion_entered(it);
          }
        });
    v.setVisibility(View.GONE);
    _item_views[item_index] = v;
  }

  static boolean can_remove_personal_candidate(boolean dictionary_enabled,
      int item_index, boolean personal_candidate)
  {
    return dictionary_enabled && item_index < Suggestions.MAX_COUNT
      && personal_candidate;
  }

  static boolean remove_personal_candidate(UserDictionary dictionary, String candidate)
  {
    return dictionary.remove(candidate);
  }

  static boolean matches_long_press_candidate(String captured_candidate,
      boolean captured_personal, String current_candidate, boolean current_personal)
  {
    return captured_personal && current_personal && captured_candidate != null
      && captured_candidate.equals(current_candidate);
  }

  /** Whether the candidates view should be shown for a given editor. */
  public static boolean should_show(EditorInfo info)
  {
    int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
    int flags = info.inputType & InputType.TYPE_MASK_FLAGS;
    switch (info.inputType & InputType.TYPE_MASK_CLASS)
    {
      case InputType.TYPE_CLASS_TEXT:
        switch (variation)
        {
          case InputType.TYPE_TEXT_VARIATION_PASSWORD:
          case InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:
          case InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD:
            return false;
          default:
            /* Editor requested that we don't show suggestions. Enable
               suggestions anyway when the flags [NO_SUGGESTIONS] and
               [AUTO_CORRECT] are present at the same time. This happens with
               Google Keep. */
            if ((flags &
                  (InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                   | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT))
                == InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
              return false;
            return true;
        }
      case InputType.TYPE_CLASS_NUMBER:
        // Beware of TYPE_NUMBER_VARIATION_PASSWORD
        return false;
      default: return false;
    }
  }
}
