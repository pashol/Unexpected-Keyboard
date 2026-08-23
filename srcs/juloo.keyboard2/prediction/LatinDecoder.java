package juloo.keyboard2.prediction;

import com.android.inputmethod.latin.BinaryDictionary;
import java.io.File;

/** Owns a single native LatinIME dictionary handle. */
public final class LatinDecoder
{
  interface NativeDecoder
  {
    long open(String path, long offset, long length);
    boolean isValid(long dictionary);
    void close(long dictionary);
  }

  public static final class InvalidDictionaryException extends RuntimeException
  {
    InvalidDictionaryException(String message) { super(message); }
  }

  public static final class Dictionary implements AutoCloseable
  {
    private final NativeDecoder nativeDecoder;
    private long handle;

    private Dictionary(NativeDecoder nativeDecoder, long handle)
    {
      this.nativeDecoder = nativeDecoder;
      this.handle = handle;
    }

    public void close()
    {
      if (handle != 0) {
        nativeDecoder.close(handle);
        handle = 0;
      }
    }

    long handle() { return handle; }
  }

  private final NativeDecoder nativeDecoder;

  LatinDecoder(NativeDecoder nativeDecoder)
  {
    this.nativeDecoder = nativeDecoder;
  }

  public LatinDecoder()
  {
    this(new NativeDecoder() {
      public long open(String path, long offset, long length)
      {
        return BinaryDictionary.open(path, offset, length);
      }

      public boolean isValid(long dictionary)
      {
        return dictionary != 0 && BinaryDictionary.validate(dictionary);
      }

      public void close(long dictionary) { BinaryDictionary.close(dictionary); }
    });
  }

  public Dictionary open(File file)
  {
    if (file == null || !file.isFile() || !file.canRead())
      throw new InvalidDictionaryException("Dictionary path is not readable");

    long handle = nativeDecoder.open(file.getPath(), 0, file.length());
    if (!nativeDecoder.isValid(handle)) {
      if (handle != 0)
        nativeDecoder.close(handle);
      throw new InvalidDictionaryException("Dictionary header is invalid");
    }
    return new Dictionary(nativeDecoder, handle);
  }
}
