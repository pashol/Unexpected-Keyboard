package juloo.keyboard2;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.DialogInterface;
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

public class SettingsActivity extends PreferenceActivity
{
  private static final int REQUEST_EXPORT_DICT = 1001;
  private static final int REQUEST_IMPORT_DICT = 1002;

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

    boolean foldableDevice = FoldStateTracker.isFoldableDevice(this);
    findPreference("user_dictionary_export").setOnPreferenceClickListener(
        new Preference.OnPreferenceClickListener() {
          @Override
          public boolean onPreferenceClick(Preference p)
          {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, getString(R.string.user_dictionary_export_filename));
            startActivityForResult(intent, REQUEST_EXPORT_DICT);
            return true;
          }
        });

    findPreference("user_dictionary_import").setOnPreferenceClickListener(
        new Preference.OnPreferenceClickListener() {
          @Override
          public boolean onPreferenceClick(Preference p)
          {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            startActivityForResult(intent, REQUEST_IMPORT_DICT);
            return true;
          }
        });

    findPreference("margin_bottom_portrait_unfolded").setEnabled(foldableDevice);
    findPreference("margin_bottom_landscape_unfolded").setEnabled(foldableDevice);
    findPreference("horizontal_margin_portrait_unfolded").setEnabled(foldableDevice);
    findPreference("horizontal_margin_landscape_unfolded").setEnabled(foldableDevice);
    findPreference("keyboard_height_unfolded").setEnabled(foldableDevice);
    findPreference("keyboard_height_landscape_unfolded").setEnabled(foldableDevice);
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data)
  {
    super.onActivityResult(requestCode, resultCode, data);
    if (resultCode != RESULT_OK || data == null) return;
    Uri uri = data.getData();
    if (uri == null) return;
    ContentResolver resolver = getContentResolver();
    UserDictionary dict = UserDictionary.getInstance();
    if (requestCode == REQUEST_EXPORT_DICT)
    {
      boolean ok = dict.exportTo(resolver, uri);
      Toast.makeText(this,
          ok ? R.string.user_dictionary_export_success : R.string.user_dictionary_export_error,
          Toast.LENGTH_SHORT).show();
    }
    else if (requestCode == REQUEST_IMPORT_DICT)
    {
      showImportDialog(resolver, uri);
    }
  }

  private void showImportDialog(final ContentResolver resolver, final Uri uri)
  {
    new AlertDialog.Builder(this)
        .setTitle(R.string.pref_user_dictionary_import_title)
        .setItems(new CharSequence[]{
            getString(R.string.user_dictionary_import_merge),
            getString(R.string.user_dictionary_import_replace)
        }, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which)
          {
            boolean replace = (which == 1);
            int count = UserDictionary.getInstance().importFrom(resolver, uri, replace);
            if (count < 0)
              Toast.makeText(SettingsActivity.this, R.string.user_dictionary_import_error, Toast.LENGTH_SHORT).show();
            else if (count == 0)
              Toast.makeText(SettingsActivity.this, R.string.user_dictionary_import_no_new_words, Toast.LENGTH_SHORT).show();
            else
              Toast.makeText(SettingsActivity.this,
                  getString(R.string.user_dictionary_import_success, count), Toast.LENGTH_SHORT).show();
          }
        })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
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
