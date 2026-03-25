package juloo.keyboard2;

import android.content.Context;
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
