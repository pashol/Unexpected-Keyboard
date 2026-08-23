package juloo.keyboard2.prediction;

import android.content.Context;
import com.android.inputmethod.latin.BinaryDictionary;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LatinDecoderNativeLifecycleTest
{
  @Test
  public void corrupt_recognized_dictionary_is_rejected_and_closed_by_native_decoder()
      throws Exception
  {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    File dictionary = new File(context.getCacheDir(), "corrupt-latinime.dict");
    writeRecognizedCorruptDictionary(dictionary);

    try {
      long handle = BinaryDictionary.open(dictionary.getPath(), 0, dictionary.length());
      assertNotEquals("Recognized dictionary did not reach native open", 0, handle);
      assertFalse("Recognized malformed dictionary was not reported corrupt",
          BinaryDictionary.validate(handle));
      BinaryDictionary.close(handle);

      assertRejected(new LatinDecoder(), dictionary);

      CountingNative nativeDecoder = new CountingNative();
      assertRejected(new LatinDecoder(nativeDecoder), dictionary);
      assertEquals("Rejected native dictionary was not closed", 1, nativeDecoder.closes);
    } finally {
      dictionary.delete();
    }
  }

  private static void assertRejected(LatinDecoder decoder, File dictionary)
  {
    try {
      decoder.open(dictionary);
      fail("Expected corrupt dictionary to be rejected");
    } catch (LatinDecoder.InvalidDictionaryException expected) {}
  }

  private static void writeRecognizedCorruptDictionary(File dictionary) throws Exception
  {
    FileOutputStream output = new FileOutputStream(dictionary);
    try {
      // LatinIME format 202 header with no trie body. Native validation opens it, then
      // detects the empty body while traversing the probe word.
      output.write(new byte[] {
          (byte)0x9b, (byte)0xc1, 0x3a, (byte)0xfe, 0x00, (byte)0xca,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x0c });
    } finally {
      output.close();
    }
  }

  private static final class CountingNative implements LatinDecoder.NativeDecoder
  {
    int closes;

    public long open(String path, long offset, long length)
    {
      return BinaryDictionary.open(path, offset, length);
    }

    public boolean isValid(long dictionary)
    {
      return dictionary != 0 && BinaryDictionary.validate(dictionary);
    }

    public void close(long dictionary)
    {
      closes++;
      BinaryDictionary.close(dictionary);
    }
  }
}
