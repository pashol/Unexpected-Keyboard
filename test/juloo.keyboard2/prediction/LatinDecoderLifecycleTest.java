package juloo.keyboard2.prediction;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import com.android.inputmethod.latin.BinaryDictionary;
import org.junit.Test;
import static org.junit.Assert.*;

public class LatinDecoderLifecycleTest
{
  @Test
  public void dictionary_facade_exposes_no_format_version_api()
  {
    for (Method method : BinaryDictionary.class.getDeclaredMethods())
      assertNotEquals("getFormatVersionNative", method.getName());
  }

  @Test
  public void dictionary_facade_exposes_lexical_and_ngram_probability_lookups()
      throws Exception
  {
    assertEquals(int.class, BinaryDictionary.class.getMethod("getProbabilityNative",
        long.class, int[].class).getReturnType());
    assertEquals(int.class, BinaryDictionary.class.getMethod("getNgramProbabilityNative",
        long.class, int[][].class, boolean[].class, int[].class).getReturnType());
  }

  @Test(expected = LatinDecoder.InvalidDictionaryException.class)
  public void invalid_path_is_rejected_before_opening_native_dictionary()
  {
    new LatinDecoder(new CountingNative()).open(new File("missing.dict"));
  }

  @Test
  public void corrupt_header_is_rejected_and_native_dictionary_is_closed()
      throws IOException
  {
    File dictionary = temporaryDictionary();
    CountingNative nativeDecoder = new CountingNative();
    nativeDecoder.valid = false;

    try {
      try {
        new LatinDecoder(nativeDecoder).open(dictionary);
        fail("Expected corrupt dictionary to be rejected");
      } catch (LatinDecoder.InvalidDictionaryException expected) {
        assertEquals(1, nativeDecoder.closes);
      }
    } finally {
      dictionary.delete();
    }
  }

  @Test
  public void repeated_open_and_close_releases_each_native_dictionary_once()
      throws IOException
  {
    File dictionary = temporaryDictionary();
    CountingNative nativeDecoder = new CountingNative();
    LatinDecoder decoder = new LatinDecoder(nativeDecoder);

    try {
      decoder.open(dictionary).close();
      decoder.open(dictionary).close();
    } finally {
      dictionary.delete();
    }

    assertEquals(2, nativeDecoder.opens);
    assertEquals(2, nativeDecoder.closes);
  }

  @Test
  public void double_close_releases_native_dictionary_once() throws IOException
  {
    File dictionary = temporaryDictionary();
    CountingNative nativeDecoder = new CountingNative();

    try {
      LatinDecoder.Dictionary open = new LatinDecoder(nativeDecoder).open(dictionary);
      open.close();
      open.close();
    } finally {
      dictionary.delete();
    }

    assertEquals(1, nativeDecoder.closes);
  }

  @Test
  public void native_pack_closes_dictionary_when_session_construction_fails() throws IOException
  {
    File dictionary = temporaryDictionary();
    CountingNative nativeDecoder = new CountingNative();
    LatinDecoder decoder = new LatinDecoder(nativeDecoder);
    try {
      try {
        new LatinPredictionDecoder.NativePack(new Resources(decoder, true, false), dictionary, "en");
        fail("expected session construction failure");
      } catch (IllegalStateException expected) {
        assertEquals(1, nativeDecoder.closes);
      }
    } finally { dictionary.delete(); }
  }

  @Test
  public void native_pack_closes_dictionary_and_session_when_initialization_fails() throws IOException
  {
    File dictionary = temporaryDictionary();
    CountingNative nativeDecoder = new CountingNative();
    LatinDecoder decoder = new LatinDecoder(nativeDecoder);
    Resources resources = new Resources(decoder, false, true);
    try {
      try {
        new LatinPredictionDecoder.NativePack(resources, dictionary, "en");
        fail("expected initialization failure");
      } catch (IllegalStateException expected) {
        assertEquals(1, nativeDecoder.closes);
        assertEquals(1, resources.session.closes);
      }
    } finally { dictionary.delete(); }
  }

  private static File temporaryDictionary() throws IOException
  {
    File dictionary = File.createTempFile("latinime", ".dict");
    FileOutputStream output = new FileOutputStream(dictionary);
    output.write(0);
    output.close();
    return dictionary;
  }

  private static final class CountingNative implements LatinDecoder.NativeDecoder
  {
    int opens;
    int closes;
    boolean valid = true;

    public long open(String path, long offset, long length)
    {
      opens++;
      return opens;
    }

    public boolean isValid(long dictionary) { return valid; }
    public void close(long dictionary) { closes++; }
  }

  private static final class Resources implements LatinPredictionDecoder.NativePackResources
  {
    final LatinDecoder decoder;
    final boolean failCreate;
    final boolean failInitialize;
    final Session session = new Session();
    Resources(LatinDecoder decoder, boolean failCreate, boolean failInitialize)
    { this.decoder = decoder; this.failCreate = failCreate; this.failInitialize = failInitialize; }
    public LatinDecoder.Dictionary open(File file) { return decoder.open(file); }
    public LatinPredictionDecoder.TraverseSession createSession(String languageTag, long size)
    {
      if (failCreate) throw new IllegalStateException("session creation failed");
      session.failInitialize = failInitialize;
      return session;
    }
  }

  private static final class Session implements LatinPredictionDecoder.TraverseSession
  {
    boolean failInitialize;
    int closes;
    public void initialize(long dictionary, int[] locale, int flags)
    { if (failInitialize) throw new IllegalStateException("initialize failed"); }
    public long handle() { return 1L; }
    public void close() { closes++; }
  }
}
