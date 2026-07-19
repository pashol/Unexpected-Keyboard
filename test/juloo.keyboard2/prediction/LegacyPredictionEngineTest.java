package juloo.keyboard2.prediction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyPredictionEngineTest
{
  @Test
  public void maps_exact_result_to_typed_candidate()
  {
    FakeSource source = new FakeSource().result("erde", "Erde", true);

    List<PredictionCandidate> result = engine(source).predict(request("erde", false, 3));

    assertCandidates(result, "Erde");
    assertSame(CandidateType.TYPED, result.get(0).getType());
    assertEquals(LegacyPredictionEngine.SOURCE_CDICT, result.get(0).getSource());
  }

  @Test
  public void falls_back_to_alternate_first_character_case_without_prefix()
  {
    FakeSource source = new FakeSource()
      .result("erde", null, false)
      .result("Erde", "Erde", true);

    assertCandidates(engine(source).predict(request("erde", false, 3)), "Erde");
    assertEquals(Arrays.asList("erde", "Erde"), source.lookups);
  }

  @Test
  public void interleaves_completions_and_corrections_in_legacy_order()
  {
    FakeSource source = new FakeSource()
      .result("erd", null, true, "Erde", "Erdbeere", "Erden")
      .distance("erd", "Erz", "Erna");

    List<PredictionCandidate> result = engine(source).predict(request("erd", false, 3));

    assertCandidates(result, "Erde", "Erz", "Erdbeere");
    assertSame(CandidateType.COMPLETION, result.get(0).getType());
    assertSame(CandidateType.CORRECTION, result.get(1).getType());
    assertSame(CandidateType.COMPLETION, result.get(2).getType());
  }

  @Test
  public void prepends_two_unique_personal_candidates_with_provenance()
  {
    FakeSource source = new FakeSource()
      .result("erd", null, true, "Erde", "Erden", "Erdbeere");
    LegacyPredictionEngine engine = new LegacyPredictionEngine(source,
        prefix -> new String[] { "erde", "Erdling", "Erdung" }, true, true);

    List<PredictionCandidate> result = engine.predict(request("erd", false, 3));

    assertCandidates(result, "Erdling", "Erde", "Erden");
    assertEquals(LegacyPredictionEngine.SOURCE_PERSONAL, result.get(0).getSource());
    assertSame(CandidateType.COMPLETION, result.get(0).getType());
    assertEquals(LegacyPredictionEngine.SOURCE_CDICT, result.get(1).getSource());
  }

  @Test
  public void capitalizes_candidates_at_sentence_start()
  {
    FakeSource source = new FakeSource().result("erd", null, true, "erde", "erdbeere");

    assertCandidates(engine(source).predict(request("erd", true, 3)),
        "Erde", "Erdbeere");
  }

  @Test
  public void promotes_typed_word_ahead_of_ambiguous_results()
  {
    FakeSource source = new FakeSource()
      .result("erde", null, true, "Erdbeere", "Erden");

    List<PredictionCandidate> result = engine(source).predict(request("erde", false, 3));

    assertCandidates(result, "erde", "Erdbeere", "Erden");
    assertSame(CandidateType.TYPED, result.get(0).getType());
    assertEquals(LegacyPredictionEngine.SOURCE_TYPED, result.get(0).getSource());
  }

  @Test
  public void keeps_a_single_unambiguous_completion_ahead_of_typed_text()
  {
    FakeSource source = new FakeSource()
      .result("Grosswan", null, true, "Grosswangen");

    assertCandidates(engine(source).predict(request("Grosswan", false, 3)),
        "Grosswangen");
  }

  @Test
  public void promotes_existing_typed_word_without_losing_personal_provenance()
  {
    FakeSource source = new FakeSource().result("personal", null, true, "System");
    LegacyPredictionEngine engine = new LegacyPredictionEngine(source,
        prefix -> new String[] { "Personal" }, true, false);

    List<PredictionCandidate> result = engine.predict(request("personal", false, 3));

    assertCandidates(result, "Personal", "System");
    assertEquals(LegacyPredictionEngine.SOURCE_PERSONAL, result.get(0).getSource());
  }

  @Test
  public void returns_no_candidates_for_empty_input_or_no_dictionary()
  {
    assertTrue(engine(new FakeSource()).predict(request("", false, 3)).isEmpty());
    LegacyPredictionEngine noSources = new LegacyPredictionEngine(
        null, null, false, true);
    assertTrue(noSources.predict(request("word", false, 3)).isEmpty());
  }

  @Test
  public void honors_request_max_results_and_lifecycle_methods_are_idempotent()
  {
    FakeSource source = new FakeSource().result("erd", null, true,
        "Erde", "Erden", "Erdbeere");
    LegacyPredictionEngine engine = engine(source);

    assertCandidates(engine.predict(request("erd", false, 2)), "Erde", "Erden");
    engine.recordFeedback(null);
    engine.resetSession();
    engine.resetSession();
    engine.close();
    engine.close();
  }

  private static LegacyPredictionEngine engine(FakeSource source)
  {
    return new LegacyPredictionEngine(source, null, false, true);
  }

  private static PredictionRequest request(String word, boolean sentenceStart, int max)
  {
    return new PredictionRequest(word, word.codePointCount(0, word.length()),
        Collections.emptyList(), sentenceStart, "und", max, 7L);
  }

  private static void assertCandidates(List<PredictionCandidate> candidates,
      String... expected)
  {
    String[] actual = new String[candidates.size()];
    for (int i = 0; i < candidates.size(); i++)
      actual[i] = candidates.get(i).getText();
    assertArrayEquals(expected, actual);
  }

  private static final class FakeSource
      implements LegacyPredictionEngine.CandidateSource
  {
    final Map<String, LegacyPredictionEngine.Lookup> results = new HashMap<>();
    final Map<String, List<String>> distances = new HashMap<>();
    final List<String> lookups = new ArrayList<>();

    FakeSource result(String query, String exact, boolean hasPrefix, String... suffixes)
    {
      results.put(query, new LegacyPredictionEngine.Lookup(
          exact, hasPrefix, Arrays.asList(suffixes)));
      return this;
    }

    FakeSource distance(String query, String... words)
    {
      distances.put(query, Arrays.asList(words));
      return this;
    }

    public LegacyPredictionEngine.Lookup find(String word, int maxResults)
    {
      lookups.add(word);
      LegacyPredictionEngine.Lookup result = results.get(word);
      return result == null
        ? new LegacyPredictionEngine.Lookup(null, false, Collections.emptyList())
        : result;
    }

    public List<String> distance(String word, int maxDistance, int maxResults)
    {
      List<String> result = distances.get(word);
      return result == null ? Collections.emptyList() : result;
    }
  }
}
