package juloo.keyboard2;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.widget.Toast;
import juloo.keyboard2.suggestions.UserDictionary;

public class SettingsActivity extends PreferenceActivity
{
  private static final int EXPORT_USER_DICTIONARY = 1;
  private static final int IMPORT_USER_DICTIONARY = 2;

  @Override
  public void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    // The preferences can't be read when in direct-boot mode. Avoid crashing
    // and don't allow changing the settings.
    // Run the config migration on this prefs as it might be different from the
    // one used by the keyboard, which have been migrated.
    try
    {
      Config.migrate(getPreferenceManager().getSharedPreferences());
    }
    catch (Exception _e) { fallbackEncrypted(); return; }
    addPreferencesFromResource(R.xml.settings);
    UserDictionary.init(this);

    boolean foldableDevice = FoldStateTracker.isFoldableDevice(this);
    findPreference("margin_bottom_portrait_unfolded").setEnabled(foldableDevice);
    findPreference("margin_bottom_landscape_unfolded").setEnabled(foldableDevice);
    findPreference("horizontal_margin_portrait_unfolded").setEnabled(foldableDevice);
    findPreference("horizontal_margin_landscape_unfolded").setEnabled(foldableDevice);
    findPreference("keyboard_height_unfolded").setEnabled(foldableDevice);
    findPreference("keyboard_height_landscape_unfolded").setEnabled(foldableDevice);
    findPreference("user_dictionary_export").setOnPreferenceClickListener(
        new Preference.OnPreferenceClickListener()
        {
          @Override public boolean onPreferenceClick(Preference preference)
          {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, "user_dictionary.txt");
            startActivityForResult(intent, EXPORT_USER_DICTIONARY);
            return true;
          }
        });
    findPreference("user_dictionary_import").setOnPreferenceClickListener(
        new Preference.OnPreferenceClickListener()
        {
          @Override public boolean onPreferenceClick(Preference preference)
          {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            startActivityForResult(intent, IMPORT_USER_DICTIONARY);
            return true;
          }
        });
  }

  @Override
  protected void onActivityResult(int request_code, int result_code, Intent data)
  {
    super.onActivityResult(request_code, result_code, data);
    if (result_code != RESULT_OK || data == null || data.getData() == null)
      return;
    Uri uri = data.getData();
    if (request_code == EXPORT_USER_DICTIONARY)
    {
      int count = UserDictionary.instance().exportTo(getContentResolver(), uri);
      Toast.makeText(this, count < 0 ? R.string.user_dictionary_export_failed
          : R.string.user_dictionary_export_success, Toast.LENGTH_SHORT).show();
    }
    else if (request_code == IMPORT_USER_DICTIONARY)
    {
      new AlertDialog.Builder(this)
        .setTitle(R.string.user_dictionary_import_title)
        .setNegativeButton(R.string.user_dictionary_import_merge,
            (dialog, which) -> import_user_dictionary(uri, false))
        .setPositiveButton(R.string.user_dictionary_import_replace,
            (dialog, which) -> import_user_dictionary(uri, true))
        .show();
    }
  }

  private void import_user_dictionary(Uri uri, boolean replace)
  {
    int count = UserDictionary.instance().importFrom(getContentResolver(), uri, replace);
    int message = import_result_message(count);
    CharSequence message_text = count > 0 ? getString(message, count) : getString(message);
    Toast.makeText(this, message_text,
        Toast.LENGTH_SHORT).show();
  }

  static int import_result_message(int count)
  {
    if (count < 0)
      return R.string.user_dictionary_import_failed;
    if (count == 0)
      return R.string.user_dictionary_import_no_new_words;
    return R.string.user_dictionary_import_success;
  }

  void fallbackEncrypted()
  {
    // Can't communicate with the user here.
    finish();
  }

  protected void onStop()
  {
    DirectBootAwarePreferences
      .copy_preferences_to_protected_storage(this,
          getPreferenceManager().getSharedPreferences());
    super.onStop();
  }
}
