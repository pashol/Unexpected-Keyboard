package juloo.keyboard2.prediction;

import android.content.Context;
import android.text.InputType;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises the packaged synthetic dictionaries through the real JNI facade. */
@RunWith(AndroidJUnit4.class)
public class LatinPredictionNativeIntegrationTest
{
  @Test
  public void english_pack_completes_unicode_and_predicts_hello_world()
  {
    LatinPredictionDecoder decoder = decoder("en");
    try {
      assertContains(decoder.predict(request("hel", "en")), "hello");
      assertContains(decoder.predict(request("caf\u00e9", "en")), "caf\u00e9");
      assertContains(decoder.predict(request("", "en", "hello")), "world");
    } finally { decoder.close(); }
  }

  @Test
  public void swiss_german_pack_preserves_variants_and_das_isch_context()
  {
    LatinPredictionDecoder decoder = decoder("gsw-CH");
    try {
      assertContains(decoder.predict(request("n", "gsw-CH")), "n\u00f6d");
      assertContains(decoder.predict(request("", "gsw-CH", "das")), "isch");
    } finally { decoder.close(); }
  }

  private static LatinPredictionDecoder decoder(String locale)
  {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    return new LatinPredictionDecoder(new LatinPredictionAssetResolver(context).resolve(locale),
        locale, Arrays.asList(
            new PredictionRequest.KeyCenter('q', 0, 0), new PredictionRequest.KeyCenter('w', 1, 0),
            new PredictionRequest.KeyCenter('e', 2, 0), new PredictionRequest.KeyCenter('r', 3, 0),
            new PredictionRequest.KeyCenter('t', 4, 0), new PredictionRequest.KeyCenter('y', 5, 0),
            new PredictionRequest.KeyCenter('u', 6, 0), new PredictionRequest.KeyCenter('i', 7, 0),
            new PredictionRequest.KeyCenter('o', 8, 0), new PredictionRequest.KeyCenter('p', 9, 0),
            new PredictionRequest.KeyCenter('a', 1, 1), new PredictionRequest.KeyCenter('s', 2, 1),
            new PredictionRequest.KeyCenter('d', 3, 1), new PredictionRequest.KeyCenter('f', 4, 1),
            new PredictionRequest.KeyCenter('g', 5, 1), new PredictionRequest.KeyCenter('h', 6, 1),
            new PredictionRequest.KeyCenter('j', 7, 1), new PredictionRequest.KeyCenter('k', 8, 1),
            new PredictionRequest.KeyCenter('l', 9, 1), new PredictionRequest.KeyCenter('c', 3, 2),
            new PredictionRequest.KeyCenter('n', 5, 2)));
  }

  private static PredictionRequest request(String text, String locale, String... previous)
  {
    return new PredictionRequest(text, text.codePointCount(0, text.length()),
        Arrays.asList(previous), false, locale, 6, 1L,
        EditorPredictionPolicy.from(InputType.TYPE_CLASS_TEXT, 0, null), Collections.emptyList());
  }

  private static void assertContains(List<PredictionCandidate> candidates, String text)
  {
    for (PredictionCandidate candidate : candidates)
      if (text.equals(candidate.getText())) return;
    fail("Missing " + text + " from " + candidates.size() + " native candidates");
  }
}
