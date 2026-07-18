package juloo.keyboard2.suggestions;

import java.io.File;
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
}
