package juloo.keyboard2.prediction;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import juloo.keyboard2.prediction.history.BoundedUserHistoryModel;
import juloo.keyboard2.prediction.history.UserHistoryPersistence;
import juloo.keyboard2.prediction.history.UserHistoryStore;
import juloo.keyboard2.prediction.history.UserHistoryModel;
import org.junit.Test;
import static org.junit.Assert.*;

public class LatinPredictionDecoderTest
{
  @Test
  public void english_prefixes_keep_native_order_when_scores_are_equal()
  {
    RecordingNative nativeFacade = new RecordingNative(
        result("hello", 100, 90, LatinPredictionDecoder.NATIVE_COMPLETION),
        result("help", 90, 90, LatinPredictionDecoder.NATIVE_COMPLETION),
        result("held", 80, 90, LatinPredictionDecoder.NATIVE_COMPLETION));
    LatinPredictionDecoder decoder = new LatinPredictionDecoder(nativeFacade, "en");

    List<PredictionCandidate> candidates = decoder.predict(request("hel", policy()));

    assertTexts(candidates, "hel", "hello", "help", "held");
    assertArrayEquals(new int[] { 'h', 'e', 'l' }, nativeFacade.input);
  }

  @Test
  public void candidates_preserve_sentence_initial_capitalization()
  {
    LatinPredictionDecoder decoder = new LatinPredictionDecoder(new RecordingNative(
        result("hello", 100, 90, LatinPredictionDecoder.NATIVE_COMPLETION),
        result("help", 90, 90, LatinPredictionDecoder.NATIVE_COMPLETION)), "en");

    assertTexts(decoder.predict(new PredictionRequest("hel", 3,
        Collections.emptyList(), true, "en", 4, 1L, policy(), Collections.emptyList())),
        "Hel", "Hello", "Help");
  }

  @Test
  public void exact_native_match_is_retained_as_the_typed_candidate()
  {
    LatinPredictionDecoder decoder = new LatinPredictionDecoder(new RecordingNative(
        result("hello", 100, 50, LatinPredictionDecoder.NATIVE_CORRECTION)), "en");

    List<PredictionCandidate> candidates = decoder.predict(request("hello", policy()));

    assertEquals(CandidateType.TYPED, candidates.get(0).getType());
    assertEquals("hello", candidates.get(0).getText());
  }

  @Test
  public void unicode_code_points_and_preceding_words_reach_the_native_facade()
  {
    RecordingNative nativeFacade = new RecordingNative(
        result("caf\u00e9", 100, 10, LatinPredictionDecoder.NATIVE_COMPLETION));
    LatinPredictionDecoder decoder = new LatinPredictionDecoder(nativeFacade, "en");

    List<PredictionCandidate> candidates = decoder.predict(new PredictionRequest(
        "caf\u00e9", 4, Arrays.asList("one", "two", "three", "four"), false, "en", 4, 1L,
        policy(), Arrays.asList(
            new PredictionRequest.KeyCenter('c', 1, 1),
            new PredictionRequest.KeyCenter('a', 2, 1),
            new PredictionRequest.KeyCenter('f', 3, 1),
            new PredictionRequest.KeyCenter(0x00e9, 4, 1))));

    assertTexts(candidates, "caf\u00e9");
    assertArrayEquals(new int[] { 'c', 'a', 'f', 0x00e9 }, nativeFacade.input);
    assertArrayEquals(new int[] { 't', 'w', 'o' }, nativeFacade.preceding[0]);
    assertArrayEquals(new int[] { 'f', 'o', 'u', 'r' }, nativeFacade.preceding[2]);
  }

  @Test
  public void supplementary_code_points_reach_native_without_string_codepoints_api()
  {
    RecordingNative nativeFacade = new RecordingNative();
    LatinPredictionDecoder decoder = new LatinPredictionDecoder(nativeFacade, "en");

    decoder.predict(request("A\ud83d\ude00", policy()));

    assertArrayEquals(new int[] { 'A', 0x1f600 }, nativeFacade.input);
  }

  @Test
  public void adjacent_key_correction_beats_a_distant_correction()
  {
    LatinPredictionDecoder decoder = new LatinPredictionDecoder(new RecordingNative(
        result("cat", 100, 10, LatinPredictionDecoder.NATIVE_CORRECTION),
        result("dog", 100, 10, LatinPredictionDecoder.NATIVE_CORRECTION)), "en");

    List<PredictionCandidate> candidates = decoder.predict(new PredictionRequest(
        "vat", 3, Collections.emptyList(), false, "en", 3, 1L, policy(), Arrays.asList(
            new PredictionRequest.KeyCenter('v', 0, 0),
            new PredictionRequest.KeyCenter('c', 1, 0),
            new PredictionRequest.KeyCenter('d', 100, 0),
            new PredictionRequest.KeyCenter('a', 2, 0),
            new PredictionRequest.KeyCenter('o', 3, 0),
            new PredictionRequest.KeyCenter('t', 4, 0),
            new PredictionRequest.KeyCenter('g', 5, 0))));

    assertTexts(candidates, "vat", "cat", "dog");
    assertTrue(candidates.get(1).getTouchEditCost()
        < candidates.get(2).getTouchEditCost());
  }

  @Test
  public void policy_filters_corrections_before_they_are_admitted()
  {
    LatinPredictionDecoder decoder = new LatinPredictionDecoder(new RecordingNative(
        result("hello", 100, 10, LatinPredictionDecoder.NATIVE_CORRECTION)), "en");
    EditorPredictionPolicy noCorrection = EditorPredictionPolicy.from(
        android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI,
        0, null);

    assertTexts(decoder.predict(request("helo", noCorrection)), "helo");
  }

  @Test
  public void empty_input_uses_context_probability_for_hello_to_world()
  {
    RecordingNative nativeFacade = new RecordingNative(
        result("there", 20, 60, LatinPredictionDecoder.NATIVE_PREDICTION),
        result("world", 10, 80, LatinPredictionDecoder.NATIVE_PREDICTION));
    LatinPredictionDecoder decoder = new LatinPredictionDecoder(nativeFacade, "en");

    assertTexts(decoder.predict(request("", policy(), "hello")), "world", "there");
    assertEquals(0, nativeFacade.input.length);
  }

  @Test
  public void swiss_german_variants_and_das_isch_prediction_are_not_normalized()
  {
    LatinPredictionDecoder decoder = new LatinPredictionDecoder(new RecordingNative(
        result("n\u00f6d", 90, 10, LatinPredictionDecoder.NATIVE_COMPLETION),
        result("nid", 80, 10, LatinPredictionDecoder.NATIVE_COMPLETION)), "gsw-CH");

    assertTexts(decoder.predict(request("n", policy())), "n", "n\u00f6d", "nid");
    decoder = new LatinPredictionDecoder(new RecordingNative(
        result("isch", 70, 90, LatinPredictionDecoder.NATIVE_PREDICTION)), "gsw-CH");
    assertTexts(decoder.predict(request("", policy(), "das")), "isch");
  }

  @Test
  public void history_reorders_swiss_variants_without_deleting_base_candidates()
  {
    BoundedUserHistoryModel history = new BoundedUserHistoryModel(() -> 0L);
    history.recordAccepted("gsw-CH", Collections.<String>emptyList(), "nid");
    LatinPredictionDecoder decoder = new LatinPredictionDecoder(new RecordingNative(
        result("n\u00f6d", 90, 10, LatinPredictionDecoder.NATIVE_COMPLETION),
        result("nid", 80, 10, LatinPredictionDecoder.NATIVE_COMPLETION),
        result("ned", 70, 10, LatinPredictionDecoder.NATIVE_COMPLETION)), "gsw-CH",
        history.snapshot("gsw-CH"));

    assertTexts(decoder.predict(request("n", policy())), "n", "nid", "n\u00f6d", "ned");
  }

  @Test
  public void accepted_feedback_updates_the_next_prediction_from_history()
  {
    UserHistoryPersistence history = new UserHistoryPersistence(
        new BoundedUserHistoryModel(() -> 0L), new EmptyStore(), "en");
    LatinPredictionDecoder decoder = new LatinPredictionDecoder(new RecordingNative(
        result("n\u00f6d", 90, 10, LatinPredictionDecoder.NATIVE_COMPLETION),
        result("nid", 80, 10, LatinPredictionDecoder.NATIVE_COMPLETION)), "en", history);
    PredictionRequest request = request("n", policy());

    decoder.predict(request);
    decoder.recordFeedback(new PredictionFeedback(FeedbackType.ACCEPTED, 1L,
        new PredictionCandidate("nid", "en", CandidateType.COMPLETION, "latinime", 0, 0, 0, 0, 0),
        "nid", 0L));

    assertTexts(decoder.predict(new PredictionRequest("n", 1, Collections.emptyList(), false,
        "en", 4, 2L, policy())), "n", "nid", "n\u00f6d");
    decoder.close();
  }

  @Test
  public void close_releases_its_immutable_native_pack_once()
  {
    RecordingNative nativeFacade = new RecordingNative();
    LatinPredictionDecoder decoder = new LatinPredictionDecoder(nativeFacade, "en");

    decoder.close();
    decoder.close();

    assertEquals(1, nativeFacade.closes);
  }

  private static PredictionRequest request(String text, EditorPredictionPolicy policy,
      String... preceding)
  {
    return new PredictionRequest(text, text.codePointCount(0, text.length()),
        Arrays.asList(preceding), false, "en", 4, 1L, policy, Collections.emptyList());
  }

  private static EditorPredictionPolicy policy()
  {
    return EditorPredictionPolicy.from(android.text.InputType.TYPE_CLASS_TEXT, 0, null);
  }

  private static LatinPredictionDecoder.NativeResult result(String text, int lexical,
      int context, int type)
  {
    return new LatinPredictionDecoder.NativeResult(text.codePoints().toArray(), lexical, context, type);
  }

  private static void assertTexts(List<PredictionCandidate> candidates, String... texts)
  {
    assertEquals(texts.length, candidates.size());
    for (int i = 0; i < texts.length; i++)
      assertEquals(texts[i], candidates.get(i).getText());
  }

  private static final class RecordingNative implements LatinPredictionDecoder.NativeFacade
  {
    private final List<LatinPredictionDecoder.NativeResult> results;
    int[] input;
    int[][] preceding;
    int closes;

    RecordingNative(LatinPredictionDecoder.NativeResult... results)
    {
      this.results = Arrays.asList(results);
    }

    public List<LatinPredictionDecoder.NativeResult> suggest(int[] input, int[] x, int[] y,
        List<PredictionRequest.KeyCenter> keyCenters, int[][] preceding, boolean sentenceStart,
        int maxResults)
    {
      this.input = input;
      this.preceding = preceding;
      return results;
    }

    public void close() { closes++; }
  }

  private static final class EmptyStore implements UserHistoryStore
  {
    public UserHistoryModel load(String locale) { return UserHistoryModel.empty(() -> 0L); }
    public void save(String locale, UserHistoryModel snapshot) {}
  }
}
