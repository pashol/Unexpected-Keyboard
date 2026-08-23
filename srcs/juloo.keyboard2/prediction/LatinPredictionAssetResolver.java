package juloo.keyboard2.prediction;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.Objects;

/** Makes a packaged immutable LatinIME pack available at a JNI-readable file path. */
public final class LatinPredictionAssetResolver
{
  private static Context applicationContext;
  private final Context context;

  public LatinPredictionAssetResolver(Context context)
  {
    this.context = Objects.requireNonNull(context, "context");
  }

  public static void initialize(Context context)
  {
    applicationContext = context.getApplicationContext();
  }

  public static File resolveActive(String locale)
  {
    if (applicationContext == null)
      throw new PredictionFailure("LatinIME pack resolver unavailable");
    return new LatinPredictionAssetResolver(applicationContext).resolve(locale);
  }

  static Context applicationContext()
  {
    if (applicationContext == null)
      throw new PredictionFailure("LatinIME pack resolver unavailable");
    return applicationContext;
  }

  public File resolve(String locale)
  {
    File directory = new File(context.getNoBackupFilesDir(), "prediction");
    File destination = new File(directory, locale + ".dict");
    if (valid(destination)) return destination;
    destination.delete();
    if (!directory.isDirectory() && !directory.mkdirs())
      throw new PredictionFailure("LatinIME pack storage unavailable");
    File temporary = new File(directory, locale + ".dict.tmp");
    try (InputStream input = context.getAssets().open("prediction/" + locale + ".dict");
         FileOutputStream output = new FileOutputStream(temporary)) {
      byte[] buffer = new byte[8192];
      for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
      output.getFD().sync();
      if (!temporary.renameTo(destination))
        throw new IOException("cannot rename prediction pack");
      if (!valid(destination)) throw new IOException("invalid prediction pack");
      return destination;
    } catch (IOException unavailable) {
      temporary.delete();
      destination.delete();
      throw new PredictionFailure("LatinIME pack unavailable", unavailable);
    }
  }

  private static boolean valid(File file)
  {
    if (!file.isFile() || !file.canRead()) return false;
    try {
      LatinDecoder.Dictionary dictionary = new LatinDecoder().open(file);
      dictionary.close();
      return true;
    } catch (LatinDecoder.InvalidDictionaryException invalid) {
      return false;
    }
  }
}
