package juloo.keyboard2.prediction;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

public class PredictionBenchmarkTest
{
  @Test
  public void calculates_quality_metrics_and_percentiles()
  {
    PredictionBenchmark.Metrics metrics = PredictionBenchmark.metricsForTesting(
        Arrays.asList(
            new PredictionBenchmark.Outcome(1, 4, true, true, false),
            new PredictionBenchmark.Outcome(2, 4, false, true, true),
            new PredictionBenchmark.Outcome(0, 4, false, false, false)),
        new long[] { 1_000_000L, 5_000_000L, 9_000_000L, 20_000_000L });

    assertEquals(1.0 / 3.0, metrics.topOneRecall, 0.0001);
    assertEquals(2.0 / 3.0, metrics.topThreeRecall, 0.0001);
    assertEquals(0.5, metrics.meanReciprocalRank, 0.0001);
    assertEquals(0.5, metrics.keystrokesSaved, 0.0001);
    assertEquals(1.0, metrics.correctionPrecision, 0.0001);
    assertEquals(0.5, metrics.reversionRate, 0.0001);
    assertEquals(5.0, metrics.p50Millis, 0.0001);
    assertEquals(20.0, metrics.p95Millis, 0.0001);
    assertEquals(20.0, metrics.p99Millis, 0.0001);
  }

  @Test
  public void defines_zero_proposal_correction_precision_as_zero()
  {
    PredictionBenchmark.Metrics metrics = PredictionBenchmark.metricsForTesting(
        Collections.singletonList(new PredictionBenchmark.Outcome(0, 0, false, false, false)),
        new long[] { 1_000_000L });

    assertEquals(0.0, metrics.correctionPrecision, 0.0001);
  }

  @Test
  public void replays_synthetic_corpora_deterministically_without_exposing_text()
      throws Exception
  {
    PredictionBenchmark benchmark = new PredictionBenchmark();
    List<PredictionBenchmark.Case> cases = PredictionBenchmark.loadSyntheticCorpora(
        new File("benchmark/corpora"));
    PredictionBenchmark.Engine legacy = PredictionBenchmark.deterministicLegacyEngine();
    PredictionBenchmark.Engine experimental = PredictionBenchmark.deterministicExperimentalEngine();

    boolean designatedOldestDevice = Boolean.getBoolean("predictionBenchmarkOldestDevice");
    PredictionBenchmark.Report first = benchmark.replay(cases, legacy, experimental,
        designatedOldestDevice);
    PredictionBenchmark.Report second = benchmark.replay(cases, legacy, experimental,
        designatedOldestDevice);
    String output = System.getProperty("predictionBenchmarkOutput");
    benchmark.writeJson(new File(output == null
        ? "build/reports/prediction-benchmark.json" : output), first);

    assertEquals(first.qualityJson(), second.qualityJson());
    assertFalse(first.toJson().contains("hello"));
    assertFalse(first.toJson().contains("isch"));
    assertTrue(first.toJson().contains("\"autocorrectionEnabled\":false"));
  }

  @Test(expected = AssertionError.class)
  public void oldest_device_gate_rejects_slow_latency()
  {
    PredictionBenchmark.enforceOldestDeviceLatencyGate(16.0, 30.0);
  }

  @Test
  public void loads_only_synthetic_english_and_swiss_german_corpora()
      throws Exception
  {
    List<PredictionBenchmark.Case> cases = PredictionBenchmark.loadSyntheticCorpora(
        new File("benchmark/corpora"));

    assertEquals(4, cases.size());
    assertEquals("en", cases.get(0).languageTag);
    assertEquals("gsw-CH", cases.get(2).languageTag);
  }

  @Test
  public void adapts_prediction_engines_to_replay_requests()
  {
    RecordingEngine engine = new RecordingEngine();
    PredictionBenchmark.Engine replayEngine = PredictionBenchmark.fromPredictionEngine(engine);

    replayEngine.predict(new PredictionBenchmark.Case("gsw-CH",
        Collections.singletonList("das"), "", "isch", false, false));

    assertEquals("gsw-CH", engine.request.getLanguageTag());
    assertEquals(Collections.singletonList("das"), engine.request.getPrecedingWords());
  }

  @Test
  public void writes_aggregate_json_without_corpus_text() throws Exception
  {
    PredictionBenchmark benchmark = new PredictionBenchmark();
    File output = File.createTempFile("prediction-benchmark", ".json");
    try
    {
      benchmark.writeJson(output, benchmark.replay(Arrays.asList(
          new PredictionBenchmark.Case("en", Collections.<String>emptyList(), "hel", "hello", true, false)),
          PredictionBenchmark.deterministicLegacyEngine(),
          PredictionBenchmark.deterministicExperimentalEngine(), false));

      String json = new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
      assertTrue(json.contains("\"top1Recall\""));
      assertFalse(json.contains("hello"));
    }
    finally { output.delete(); }
  }

  private static final class RecordingEngine implements PredictionEngine
  {
    PredictionRequest request;

    @Override public List<PredictionCandidate> predict(PredictionRequest request)
    {
      this.request = request;
      return Collections.emptyList();
    }

    @Override public void recordFeedback(PredictionFeedback feedback) { }
    @Override public void resetSession() { }
    @Override public void close() { }
  }
}
