package juloo.keyboard2.prediction;

import static org.junit.Assert.assertArrayEquals;

import java.util.Arrays;
import org.junit.Test;

public class LatinimeDictionaryTest
{
  @Test public void preceding_words_are_converted_to_code_points_in_recency_order()
  {
    int[][] words = LatinimeDictionary.code_points(Arrays.asList("first", "a\uD83D\uDE00", "last"));

    assertArrayEquals(new int[] { 'l', 'a', 's', 't' }, words[0]);
    assertArrayEquals(new int[] { 'a', 0x1F600 }, words[1]);
    assertArrayEquals(new int[] { 'f', 'i', 'r', 's', 't' }, words[2]);
  }

  @Test public void swiss_german_unicode_and_apostrophes_are_preserved_as_code_points()
  {
    int[][] words = LatinimeDictionary.code_points(Arrays.asList("nöd", "d'Frau"));

    assertArrayEquals(new int[] { 'd', '\'', 'F', 'r', 'a', 'u' }, words[0]);
    assertArrayEquals(new int[] { 'n', 'ö', 'd' }, words[1]);
  }
}
