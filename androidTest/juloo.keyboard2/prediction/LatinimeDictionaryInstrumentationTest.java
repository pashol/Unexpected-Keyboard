package juloo.keyboard2.prediction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.List;
import com.android.inputmethod.latin.BinaryDictionary;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LatinimeDictionaryInstrumentationTest
{
  @Test public void generated_fixture_returns_hello_world() throws Exception
  {
    File dictionary_file = copy_fixture();
    LatinimeDictionary dictionary = LatinimeDictionary.open(dictionary_file);

    List<PredictionCandidate> predictions = dictionary.next_words(Arrays.asList("hello"), 3);
    assertFalse(predictions.isEmpty());
    assertEquals("world", predictions.get(0).text());

    dictionary.close();
  }

  @Test public void opening_a_missing_file_is_rejected() throws Exception
  {
    File missing = new File(cache_directory(), "missing.dict");
    missing.delete();
    assertFalse(missing.exists());
    assert_open_rejected(missing);
  }

  @Test public void opening_a_corrupt_header_is_rejected() throws Exception
  {
    File corrupt = new File(cache_directory(), "corrupt.dict");
    FileOutputStream output = new FileOutputStream(corrupt);
    output.write(new byte[] { 0, 1, 2, 3 });
    output.close();

    assert_open_rejected(corrupt);
  }

  @Test public void malformed_format202_fixture_is_closed_after_native_validation() throws Exception
  {
    File corrupt = copy_malformed_format202_fixture();
    long native_dictionary = BinaryDictionary.open(corrupt.getAbsolutePath(), corrupt.length());
    assertTrue(native_dictionary != 0);
    BinaryDictionary.close(native_dictionary);
    final int[] close_calls = { 0 };
    LatinimeDictionary.set_close_observer(new LatinimeDictionary.CloseObserver()
    {
      public void dictionary_closed() { close_calls[0]++; }
    });
    try
    {
      assert_open_rejected(corrupt);
      assertEquals(1, close_calls[0]);
    }
    finally
    {
      LatinimeDictionary.set_close_observer(null);
    }
  }

  @Test public void repeated_open_and_close_of_the_fixture_succeeds() throws Exception
  {
    File dictionary_file = copy_fixture();
    for (int i = 0; i < 2; ++i)
      LatinimeDictionary.open(dictionary_file).close();
  }

  @Test public void dictionary_close_is_idempotent() throws Exception
  {
    LatinimeDictionary dictionary = LatinimeDictionary.open(copy_fixture());
    dictionary.close();
    dictionary.close();
  }

  private void assert_open_rejected(File file) throws Exception
  {
    try
    {
      LatinimeDictionary.open(file);
      fail();
    }
    catch (IOException expected) {}
  }

  private File copy_fixture() throws Exception
  {
    return copy_fixture("minimal_en.dict");
  }

  private File copy_malformed_format202_fixture() throws Exception
  {
    File target = copy_fixture("malformed_format202.dict");
    RandomAccessFile file = new RandomAccessFile(target, "rw");
    file.seek(8);
    int header_size = file.readInt();
    file.seek(header_size);
    file.writeByte(0xff);
    file.close();
    return target;
  }

  private File copy_fixture(String name) throws Exception
  {
    File target = new File(cache_directory(), name);
    InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets()
        .open("latinime/minimal_en.dict");
    FileOutputStream output = new FileOutputStream(target);
    byte[] buffer = new byte[8192];
    int count;
    while ((count = input.read(buffer)) != -1)
      output.write(buffer, 0, count);
    output.close();
    input.close();
    return target;
  }

  private File cache_directory()
  {
    return InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
  }
}
