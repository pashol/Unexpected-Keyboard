package juloo.keyboard2.prediction;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class PredictionSessionControllerTest
{
  private static final PredictionRequest REQUEST = new PredictionRequest(
      Collections.singletonList("hello"), 3, 1);

  @Test
  public void toggling_an_active_session_off_detaches_the_old_decoder_before_requests()
  {
    RecordingEngine decoder = new RecordingEngine("world");
    PredictionSessionController session = new PredictionSessionController();
    session.replace(new PredictionEngineController(true, decoder, new EmptyEngine()));

    assertEquals("world", session.predict(REQUEST).get(0).text());
    session.replace(null);

    assertTrue(session.predict(REQUEST).isEmpty());
    assertEquals(1, decoder.predict_calls);
    assertEquals(1, decoder.close_calls);
  }

  @Test
  public void replacing_for_a_subtype_change_closes_the_old_decoder()
  {
    RecordingEngine oldDecoder = new RecordingEngine("old");
    RecordingEngine newDecoder = new RecordingEngine("new");
    PredictionSessionController session = new PredictionSessionController();
    session.replace(new PredictionEngineController(true, oldDecoder, new EmptyEngine()));

    session.replace(new PredictionEngineController(true, newDecoder, new EmptyEngine()));

    assertEquals("new", session.predict(REQUEST).get(0).text());
    assertEquals(0, oldDecoder.predict_calls);
    assertEquals(1, oldDecoder.close_calls);
  }

  @Test
  public void finishing_an_input_view_closes_and_detaches_the_decoder()
  {
    RecordingEngine decoder = new RecordingEngine("world");
    PredictionSessionController session = new PredictionSessionController();
    session.replace(new PredictionEngineController(true, decoder, new EmptyEngine()));

    session.finish();

    assertNull(session.controller());
    assertTrue(session.predict(REQUEST).isEmpty());
    assertEquals(1, decoder.close_calls);
  }

  @Test
  public void production_registry_selects_swiss_german_without_a_german_fallback() throws IOException
  {
    String registry = "{\"format_version\":202,\"packs\":["
      + "{\"dictionary\":\"de.dict\",\"locale\":\"de\",\"manifest\":\"de.json\",\"state\":\"ready\"},"
      + "{\"dictionary\":\"de-CH.dict\",\"locale\":\"de-CH\",\"manifest\":\"de-CH.json\",\"state\":\"ready\"},"
      + "{\"dictionary\":\"gsw.dict\",\"locale\":\"gsw\",\"manifest\":\"gsw.json\",\"state\":\"ready\"},"
      + "{\"dictionary\":\"gsw-CH.dict\",\"locale\":\"gsw-CH\",\"manifest\":\"gsw-CH.json\",\"state\":\"ready\"}]}";

    assertEquals("gsw-CH.dict", ProductionPredictionPack.select(registry, "gsw-CH").dictionary_asset());
    assertEquals("gsw.dict", ProductionPredictionPack.select(registry, "gsw").dictionary_asset());
    assertEquals("gsw.dict", ProductionPredictionPack.select(registry, "gsw-LI").dictionary_asset());
    assertEquals("de-CH.dict", ProductionPredictionPack.select(registry, "de-CH").dictionary_asset());
    assertNull(ProductionPredictionPack.select(registry, "fr-FR"));
  }

  @Test public void production_registry_rejects_nested_fields_and_unsafe_asset_names() throws IOException
  {
    String nested = "{\"format_version\":202,\"packs\":[{\"dictionary\":{\"asset\":\"gsw.dict\"},"
      + "\"locale\":\"gsw\",\"manifest\":\"gsw.json\",\"state\":\"ready\"}]}";
    String traversal = "{\"format_version\":202,\"packs\":[{\"dictionary\":\"../gsw.dict\","
      + "\"locale\":\"gsw\",\"manifest\":\"gsw.json\",\"state\":\"ready\"}]}";
    String unexpected_top_level = "{\"packs\":[],\"dictionary\":\"gsw.dict\"}";

    assert_rejected_registry(nested);
    assert_rejected_registry(traversal);
    assert_rejected_registry(unexpected_top_level);
  }

  @Test public void production_manifest_rejects_nested_or_unexpected_fields() throws IOException
  {
    ProductionPredictionPack pack = ProductionPredictionPack.select(
        "{\"format_version\":202,\"packs\":[{\"dictionary\":\"gsw.dict\","
        + "\"locale\":\"gsw\",\"manifest\":\"gsw.json\",\"state\":\"ready\"}]}", "gsw");
    String valid = "{\"combined_source_sha256\":\"a\",\"compiler\":{},\"format_version\":202,"
        + "\"locale\":\"gsw\",\"output_sha256\":\"" + sixty_four_zeros() + "\","
        + "\"sources\":{},\"timestamp\":\"1970-01-01T00:00:00Z\"}";

    ProductionPredictionPack.with_manifest(pack, valid);
    assert_rejected_manifest(pack, valid.replace("\"output_sha256\":\"" + sixty_four_zeros() + "\"",
        "\"output_sha256\":{}"));
    assert_rejected_manifest(pack, valid.substring(0, valid.length() - 1) + ",\"extra\":true}");
  }

  private static void assert_rejected_registry(String registry)
  {
    try
    {
      ProductionPredictionPack.select(registry, "gsw");
      fail("Registry must be rejected");
    }
    catch (IOException expected) {}
  }

  private static void assert_rejected_manifest(ProductionPredictionPack pack, String manifest)
  {
    try
    {
      ProductionPredictionPack.with_manifest(pack, manifest);
      fail("Manifest must be rejected");
    }
    catch (IOException expected) {}
  }

  private static String sixty_four_zeros()
  {
    return "0000000000000000000000000000000000000000000000000000000000000000";
  }

  private static final class RecordingEngine implements PredictionEngine
  {
    final List<PredictionCandidate> results;
    int predict_calls;
    int close_calls;

    RecordingEngine(String result)
    {
      results = Arrays.asList(new PredictionCandidate(result, 1f));
    }

    public List<PredictionCandidate> predict(PredictionRequest request)
    {
      predict_calls++;
      return results;
    }
    public void reset_session() {}
    public void close() { close_calls++; }
  }

  private static final class EmptyEngine implements PredictionEngine
  {
    public List<PredictionCandidate> predict(PredictionRequest request)
    {
      return Collections.emptyList();
    }
    public void reset_session() {}
    public void close() {}
  }
}
