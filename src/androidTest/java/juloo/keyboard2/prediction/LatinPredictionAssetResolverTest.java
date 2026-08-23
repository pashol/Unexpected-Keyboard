package juloo.keyboard2.prediction;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LatinPredictionAssetResolverTest
{
  @Test
  public void english_pack_is_copied_from_assets_to_private_storage()
  {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    File pack = new LatinPredictionAssetResolver(context).resolve("en");

    assertTrue(pack.isFile());
    assertTrue(pack.canRead());
    assertEquals("en.dict", pack.getName());
  }

  @Test(expected = PredictionFailure.class)
  public void missing_locale_fails_recoverably()
  {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    new LatinPredictionAssetResolver(context).resolve("zz");
  }

  @Test
  public void truncated_cached_pack_is_replaced_from_the_asset() throws Exception
  {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    File directory = new File(context.getNoBackupFilesDir(), "prediction");
    directory.mkdirs();
    File cached = new File(directory, "en.dict");
    try (FileOutputStream output = new FileOutputStream(cached)) { output.write(0); }

    File pack = new LatinPredictionAssetResolver(context).resolve("en");

    assertTrue(pack.length() > 1);
  }
}
