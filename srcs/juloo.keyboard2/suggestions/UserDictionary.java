package juloo.keyboard2.suggestions;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Collections;

/** Persistent, user-managed words for the suggestion row. */
public final class UserDictionary
{
  private static volatile UserDictionary _instance;
  private final File _file;
  private final ArrayList<String> _words = new ArrayList<String>();
  private final Object _words_lock = new Object();
  /** Serializes durable writes without blocking candidate lookups. */
  private final Object _mutation_lock = new Object();

  public static synchronized void init(Context context)
  {
    if (_instance == null)
      _instance = new UserDictionary(new File(context.getFilesDir(), "user_words.txt"));
  }

  public static UserDictionary instance()
  {
    return _instance;
  }

  UserDictionary(File file)
  {
    _file = file;
    load();
  }

  File file()
  {
    return _file;
  }

  public boolean contains(String word)
  {
    synchronized (_words_lock)
    {
      return index_of(_words, word) >= 0;
    }
  }

  public boolean add(String word)
  {
    synchronized (_mutation_lock)
    {
      ArrayList<String> words = snapshot();
      if (!add_to(words, word) || !persist(words))
        return false;
      replace_words(words);
      return true;
    }
  }

  public boolean remove(String word)
  {
    synchronized (_mutation_lock)
    {
      ArrayList<String> words = snapshot();
      int index = index_of(words, word);
      if (index < 0)
        return false;
      words.remove(index);
      if (!persist(words))
        return false;
      replace_words(words);
      return true;
    }
  }

  public String[] find_prefix(String prefix, int max_count)
  {
    if (max_count <= 0)
      return new String[0];
    ArrayList<String> words = snapshot();
    ArrayList<String> results = new ArrayList<String>();
    for (int i = 0; i < words.size() && results.size() < max_count; i++)
      if (words.get(i).equalsIgnoreCase(prefix))
        results.add(words.get(i));
    for (int i = 0; i < words.size() && results.size() < max_count; i++)
      if (words.get(i).regionMatches(true, 0, prefix, 0, prefix.length())
          && !words.get(i).equalsIgnoreCase(prefix))
        results.add(words.get(i));
    return results.toArray(new String[results.size()]);
  }

  public int exportTo(ContentResolver resolver, Uri uri)
  {
    ArrayList<String> words = snapshot();
    try (OutputStream stream = resolver.openOutputStream(uri))
    {
      if (stream == null)
        return -1;
      write_words(stream, words);
      return words.size();
    }
    catch (IOException | SecurityException e) { return -1; }
  }

  public int importFrom(ContentResolver resolver, Uri uri, boolean replace)
  {
    try (InputStream stream = resolver.openInputStream(uri))
    {
      if (stream == null)
        return -1;
      return import_stream(stream, replace);
    }
    catch (IOException | SecurityException e) { return -1; }
  }

  int import_lines(Iterable<String> lines, boolean replace)
  {
    synchronized (_mutation_lock)
    {
      ArrayList<String> words = replace ? new ArrayList<String>() : snapshot();
      int added = 0;
      for (String line : lines)
        if (add_to(words, line))
          added++;
      if (!persist(words))
        return -1;
      replace_words(words);
      return added;
    }
  }

  private int import_stream(InputStream stream, boolean replace)
  {
    ArrayList<String> lines = new ArrayList<String>();
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(stream, strict_utf8_decoder())))
    {
      String line;
      while ((line = reader.readLine()) != null)
        lines.add(line);
    }
    catch (IOException e) { return -1; }
    return import_lines(lines, replace);
  }

  private void load()
  {
    try (InputStream stream = new FileInputStream(_file))
    {
      import_stream_without_persist(stream);
    }
    catch (IOException | SecurityException e) { }
  }

  private void import_stream_without_persist(InputStream stream) throws IOException
  {
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(stream, StandardCharsets.UTF_8)))
    {
      String line;
      while ((line = reader.readLine()) != null)
        add_without_persist(line);
    }
  }

  private boolean add_without_persist(String word)
  {
    synchronized (_words_lock)
    {
      return add_to(_words, word);
    }
  }

  private static boolean add_to(ArrayList<String> words, String word)
  {
    String normalized = valid_word(word);
    if (normalized == null)
      return false;
    for (String existing : words)
      if (existing.equalsIgnoreCase(normalized))
        return false;
    words.add(normalized);
    Collections.sort(words, String.CASE_INSENSITIVE_ORDER);
    return true;
  }

  private static CharsetDecoder strict_utf8_decoder()
  {
    return StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT);
  }

  private static String valid_word(String word)
  {
    if (word == null)
      return null;
    word = word.trim();
    return word.length() >= 3 ? word : null;
  }

  String[] snapshot_words()
  {
    ArrayList<String> words = snapshot();
    return words.toArray(new String[words.size()]);
  }

  private ArrayList<String> snapshot()
  {
    synchronized (_words_lock)
    {
      return new ArrayList<String>(_words);
    }
  }

  private void replace_words(ArrayList<String> words)
  {
    synchronized (_words_lock)
    {
      _words.clear();
      _words.addAll(words);
    }
  }

  private static int index_of(ArrayList<String> words, String word)
  {
    if (word == null)
      return -1;
    for (int i = 0; i < words.size(); i++)
      if (words.get(i).equalsIgnoreCase(word))
        return i;
    return -1;
  }

  private boolean persist(ArrayList<String> words)
  {
    File parent = _file.getAbsoluteFile().getParentFile();
    File temporary = null;
    try
    {
      temporary = File.createTempFile(".user_words", ".tmp", parent);
      try (FileOutputStream stream = new FileOutputStream(temporary))
      {
        write_words(stream, words);
        stream.getFD().sync();
      }
      if (!temporary.renameTo(_file))
        return false;
      return true;
    }
    catch (IOException | SecurityException e) { return false; }
    finally
    {
      if (temporary != null && temporary.exists())
        temporary.delete();
    }
  }

  private void write_words(OutputStream stream, Iterable<String> words) throws IOException
  {
    BufferedWriter writer = new BufferedWriter(
        new OutputStreamWriter(stream, StandardCharsets.UTF_8));
    for (String word : words)
    {
      writer.write(word);
      writer.newLine();
    }
    writer.flush();
  }
}
