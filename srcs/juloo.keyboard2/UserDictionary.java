package juloo.keyboard2;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.util.*;

public final class UserDictionary
{
  private static UserDictionary _instance = null;

  /** Call once in Keyboard2.onCreate(), after Config.initGlobalConfig(). */
  public static void init(Context context)
  {
    if (_instance == null) _instance = new UserDictionary(context.getFilesDir());
  }

  public static UserDictionary getInstance() { return _instance; }

  private final ArrayList<String> _words = new ArrayList<>();  // newest-first, as-typed
  private final File _file;

  private UserDictionary(File filesDir)
  {
    _file = new File(filesDir, "user_words.txt");
    load();
  }

  /** Up to [limit] words whose lowercase form starts with [prefix] (case-insensitive). */
  public List<String> find_prefix(String prefix, int limit)
  {
    String lower = prefix.toLowerCase();
    List<String> result = new ArrayList<>();
    for (String w : _words)
    {
      if (w.toLowerCase().startsWith(lower)) result.add(w);
      if (result.size() >= limit) break;
    }
    return result;
  }

  public boolean contains(String word)
  {
    String lower = word.toLowerCase();
    for (String w : _words)
      if (w.toLowerCase().equals(lower)) return true;
    return false;
  }

  public void add(String word)
  {
    if (word.length() < 3 || contains(word)) return;
    _words.add(0, word);
    save();
  }

  public void remove(String word)
  {
    String lower = word.toLowerCase();
    for (int i = 0; i < _words.size(); i++)
    {
      if (_words.get(i).toLowerCase().equals(lower))
      {
        _words.remove(i);
        save();
        return;
      }
    }
  }

  /** Write all words to [uri] (one word per line, UTF-8). Returns true on success. */
  public boolean exportTo(ContentResolver resolver, Uri uri)
  {
    try (OutputStream os = resolver.openOutputStream(uri);
         PrintWriter w = new PrintWriter(new OutputStreamWriter(os, "UTF-8")))
    {
      for (String word : _words) w.println(word);
      return true;
    }
    catch (IOException e) { return false; }
  }

  /** Import words from [uri]. If [replace] is true, clears existing words first.
      Returns the number of words added, or -1 on IO error. */
  public int importFrom(ContentResolver resolver, Uri uri, boolean replace)
  {
    try (InputStream is = resolver.openInputStream(uri);
         BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8")))
    {
      if (replace) _words.clear();
      int count = 0;
      String line;
      while ((line = r.readLine()) != null)
      {
        line = line.trim();
        if (line.length() >= 3 && !contains(line))
        {
          _words.add(0, line);
          count++;
        }
      }
      if (count > 0 || replace) save();
      return count;
    }
    catch (IOException e) { return -1; }
  }

  private void load()
  {
    if (!_file.exists()) return;
    try (BufferedReader r = new BufferedReader(new FileReader(_file)))
    {
      String line;
      while ((line = r.readLine()) != null)
      {
        line = line.trim();
        if (!line.isEmpty()) _words.add(line);
      }
    }
    catch (IOException e) { /* silently ignore */ }
  }

  private void save()
  {
    try (PrintWriter w = new PrintWriter(new FileWriter(_file, false)))
    {
      for (String word : _words) w.println(word);
    }
    catch (IOException e) { /* silently ignore */ }
  }
}
