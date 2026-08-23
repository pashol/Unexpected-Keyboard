package juloo.keyboard2.prediction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LatinimeDictionaryInstrumentationTest
{
  @Test public void generated_fixture_returns_hello_world_and_closes_idempotently() throws Exception
  {
    File dictionary_file = copy_fixture();
    LatinimeDictionary dictionary = LatinimeDictionary.open(dictionary_file);

    List<PredictionCandidate> predictions = dictionary.next_words(Arrays.asList("hello"), 3);
    assertFalse(predictions.isEmpty());
    assertEquals("world", predictions.get(0).text());

    dictionary.close();
    dictionary.close();
  }

  private File copy_fixture() throws Exception
  {
    File target = new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
        .getCacheDir(), "minimal_en.dict");
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
}
