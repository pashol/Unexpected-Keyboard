package juloo.keyboard2.suggestions;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class UserDictionaryTest
{
  private UserDictionary dictionary() throws Exception
  {
    File directory = Files.createTempDirectory("user-dictionary").toFile();
    return new UserDictionary(new File(directory, "user_words.txt"));
  }

  private int import_stream(UserDictionary dictionary, byte[] input, boolean replace)
      throws Exception
  {
    Method method = UserDictionary.class.getDeclaredMethod("import_stream",
        java.io.InputStream.class, boolean.class);
    method.setAccessible(true);
    return ((Integer)method.invoke(dictionary, new ByteArrayInputStream(input), replace)).intValue();
  }

  @Test
  public void persists_entered_casing_and_matches_case_insensitively() throws Exception
  {
    UserDictionary dictionary = dictionary();
    assertTrue(dictionary.add("iPhone"));
    assertTrue(dictionary.contains("IPHONE"));
    assertArrayEquals(new String[] { "iPhone" }, dictionary.find_prefix("iph", 2));

    UserDictionary reloaded = new UserDictionary(dictionary.file());
    assertTrue(reloaded.contains("iphone"));
    assertArrayEquals(new String[] { "iPhone" }, reloaded.find_prefix("iph", 2));
    assertTrue(reloaded.remove("IPHONE"));
    assertFalse(reloaded.contains("iPhone"));
  }

  @Test
  public void finds_exact_word_before_prefixes() throws Exception
  {
    UserDictionary dictionary = dictionary();
    dictionary.add("Erd");
    dictionary.add("Erde");
    dictionary.add("Erden");

    assertArrayEquals(new String[] { "Erd", "Erde", "Erden" },
        dictionary.find_prefix("erd", 3));
  }

  @Test
  public void imports_merge_and_replace_valid_lines() throws Exception
  {
    UserDictionary dictionary = dictionary();
    dictionary.add("Kept");

    assertEquals(1, dictionary.import_lines(Arrays.asList(" Added ", "no", "", "KEPT"), false));
    assertArrayEquals(new String[] { "Added", "Kept" }, dictionary.find_prefix("", 3));

    assertEquals(1, dictionary.import_lines(Arrays.asList("Fresh", "x"), true));
    assertArrayEquals(new String[] { "Fresh" }, dictionary.find_prefix("", 3));
  }

  @Test
  public void ignores_invalid_local_file_content() throws Exception
  {
    UserDictionary dictionary = dictionary();
    Files.write(dictionary.file().toPath(), "ok\nFine\n  Valid  \n".getBytes(StandardCharsets.UTF_8));

    UserDictionary reloaded = new UserDictionary(dictionary.file());
    assertFalse(reloaded.contains("ok"));
    assertTrue(reloaded.contains("fine"));
    assertTrue(reloaded.contains("valid"));
  }

  @Test
  public void keeps_words_when_replace_import_has_invalid_utf8() throws Exception
  {
    UserDictionary dictionary = dictionary();
    dictionary.add("Existing");

    assertEquals(-1, import_stream(dictionary,
        new byte[] { 'N', 'e', 'w', (byte)0xc3, 0x28 }, true));
    assertArrayEquals(new String[] { "Existing" }, dictionary.find_prefix("", 2));
  }

  @Test
  public void keeps_words_when_replace_persistence_fails() throws Exception
  {
    UserDictionary dictionary = dictionary();
    dictionary.add("Existing");
    assertTrue(dictionary.file().setWritable(false, false));
    assertTrue(dictionary.file().getParentFile().setWritable(false, false));
    try
    {
      assertEquals(-1, dictionary.import_lines(Arrays.asList("Replacement"), true));
      assertArrayEquals(new String[] { "Existing" }, dictionary.find_prefix("", 2));
    }
    finally
    {
      dictionary.file().getParentFile().setWritable(true, false);
      dictionary.file().setWritable(true, false);
    }
  }

  @Test
  public void add_keeps_existing_words_when_persistence_fails() throws Exception
  {
    UserDictionary dictionary = dictionary();
    dictionary.add("Existing");
    assertTrue(dictionary.file().getParentFile().setWritable(false, false));
    try
    {
      assertFalse(dictionary.add("Added"));
      assertArrayEquals(new String[] { "Existing" }, dictionary.find_prefix("", 2));
    }
    finally
    {
      dictionary.file().getParentFile().setWritable(true, false);
    }
  }

  @Test
  public void remove_keeps_existing_words_when_persistence_fails() throws Exception
  {
    UserDictionary dictionary = dictionary();
    dictionary.add("Existing");
    assertTrue(dictionary.file().getParentFile().setWritable(false, false));
    try
    {
      assertFalse(dictionary.remove("Existing"));
      assertArrayEquals(new String[] { "Existing" }, dictionary.find_prefix("", 2));
    }
    finally
    {
      dictionary.file().getParentFile().setWritable(true, false);
    }
  }

  @Test
  public void snapshots_remain_stable_after_later_mutations() throws Exception
  {
    UserDictionary dictionary = dictionary();
    dictionary.add("Existing");
    String[] snapshot = dictionary.snapshot_words();

    dictionary.add("Later");

    assertArrayEquals(new String[] { "Existing" }, snapshot);
    assertArrayEquals(new String[] { "Existing", "Later" }, dictionary.snapshot_words());
  }
}
