package juloo.keyboard2.prediction;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Replays fixed benchmark cases and emits only aggregate measurements. */
public final class PredictionBenchmark
{
  public interface Engine
  {
    List<PredictionCandidate> predict(Case benchmarkCase);
  }

  public static final class Case
  {
    final String languageTag;
    final List<String> precedingWords;
    final String typed;
    final String target;
    final boolean acceptedCorrection;
    final boolean reverted;

    public Case(String languageTag, List<String> precedingWords, String typed,
        String target, boolean acceptedCorrection, boolean reverted)
    {
      this.languageTag = languageTag;
      this.precedingWords = Collections.unmodifiableList(new ArrayList<String>(precedingWords));
      this.typed = typed;
      this.target = target;
      this.acceptedCorrection = acceptedCorrection;
      this.reverted = reverted;
    }
  }

  public static final class Outcome
  {
    final int rank;
    final int potentialKeystrokesSaved;
    final boolean correctionProposed;
    final boolean correctionAccepted;
    final boolean reverted;

    public Outcome(int rank, int potentialKeystrokesSaved,
        boolean correctionProposed, boolean correctionAccepted, boolean reverted)
    {
      this.rank = rank;
      this.potentialKeystrokesSaved = potentialKeystrokesSaved;
      this.correctionProposed = correctionProposed;
      this.correctionAccepted = correctionAccepted;
      this.reverted = reverted;
    }
  }

  public static final class Metrics
  {
    final double topOneRecall;
    final double topThreeRecall;
    final double meanReciprocalRank;
    final double keystrokesSaved;
    final double correctionPrecision;
    final double reversionRate;
    final double p50Millis;
    final double p95Millis;
    final double p99Millis;

    Metrics(double topOneRecall, double topThreeRecall, double meanReciprocalRank,
        double keystrokesSaved, double correctionPrecision, double reversionRate,
        double p50Millis, double p95Millis, double p99Millis)
    {
      this.topOneRecall = topOneRecall;
      this.topThreeRecall = topThreeRecall;
      this.meanReciprocalRank = meanReciprocalRank;
      this.keystrokesSaved = keystrokesSaved;
      this.correctionPrecision = correctionPrecision;
      this.reversionRate = reversionRate;
      this.p50Millis = p50Millis;
      this.p95Millis = p95Millis;
      this.p99Millis = p99Millis;
    }
  }

  public static final class Report
  {
    final Metrics legacy;
    final Metrics experimental;

    Report(Metrics legacy, Metrics experimental)
    {
      this.legacy = legacy;
      this.experimental = experimental;
    }

    public String qualityJson()
    {
      return "{\"autocorrectionEnabled\":false,\"legacy\":" + qualityJson(legacy)
          + ",\"experimental\":" + qualityJson(experimental) + "}";
    }

    public String toJson()
    {
      return "{\"autocorrectionEnabled\":false,\"legacy\":" + metricsJson(legacy)
          + ",\"experimental\":" + metricsJson(experimental) + "}";
    }

    private static String qualityJson(Metrics metrics)
    {
      return "{\"top1Recall\":" + metrics.topOneRecall
          + ",\"top3Recall\":" + metrics.topThreeRecall
          + ",\"mrr\":" + metrics.meanReciprocalRank
          + ",\"keystrokesSaved\":" + metrics.keystrokesSaved
          + ",\"correctionPrecision\":" + metrics.correctionPrecision
          + ",\"reversionRate\":" + metrics.reversionRate + "}";
    }

    private static String metricsJson(Metrics metrics)
    {
      return qualityJson(metrics).substring(0, qualityJson(metrics).length() - 1)
          + ",\"p50Ms\":" + metrics.p50Millis
          + ",\"p95Ms\":" + metrics.p95Millis
          + ",\"p99Ms\":" + metrics.p99Millis + "}";
    }
  }

  public Report replay(List<Case> cases, Engine legacy, Engine experimental,
      boolean designatedOldestDevice)
  {
    Metrics legacyMetrics = replay(cases, legacy);
    Metrics experimentalMetrics = replay(cases, experimental);
    if (designatedOldestDevice)
      enforceOldestDeviceLatencyGate(experimentalMetrics.p95Millis, experimentalMetrics.p99Millis);
    return new Report(legacyMetrics, experimentalMetrics);
  }

  public static List<Case> loadSyntheticCorpora(File directory) throws IOException
  {
    List<Case> cases = new ArrayList<Case>();
    for (String fileName : new String[] { "en.synthetic.tsv", "gsw-CH.synthetic.tsv" })
    {
      BufferedReader reader = new BufferedReader(new FileReader(new File(directory, fileName)));
      try
      {
        String line;
        while ((line = reader.readLine()) != null)
        {
          if (line.isEmpty() || line.charAt(0) == '#') continue;
          String[] fields = line.split("\\t", -1);
          if (fields.length != 6) throw new IOException("Invalid synthetic benchmark case");
          List<String> preceding = fields[1].isEmpty() ? Collections.<String>emptyList()
              : Arrays.asList(fields[1].split(" "));
          cases.add(new Case(fields[0], preceding, fields[2], fields[3],
              Boolean.parseBoolean(fields[4]), Boolean.parseBoolean(fields[5])));
        }
      }
      finally { reader.close(); }
    }
    return cases;
  }

  public static Engine fromPredictionEngine(final PredictionEngine engine)
  {
    return new Engine()
    {
      private long generation;

      @Override public List<PredictionCandidate> predict(Case benchmarkCase)
      {
        PredictionRequest request = new PredictionRequest(benchmarkCase.typed,
            benchmarkCase.typed.codePointCount(0, benchmarkCase.typed.length()),
            benchmarkCase.precedingWords, benchmarkCase.precedingWords.isEmpty(),
            benchmarkCase.languageTag, 3, ++generation,
            EditorPredictionPolicy.from(android.text.InputType.TYPE_CLASS_TEXT, 0, null));
        return engine.predict(request);
      }
    };
  }

  public static Engine deterministicLegacyEngine()
  {
    return fromPredictionEngine(new LegacyPredictionEngine(new DeterministicLegacyPack(), null,
        false, false));
  }

  public static Engine deterministicExperimentalEngine()
  {
    return fromPredictionEngine(new LatinPredictionDecoder(new DeterministicLatinImePack(), "en"));
  }

  public void writeJson(File output, Report report) throws IOException
  {
    File parent = output.getParentFile();
    if (parent != null && !parent.exists() && !parent.mkdirs())
      throw new IOException("Cannot create benchmark report directory");
    Writer writer = new OutputStreamWriter(new FileOutputStream(output), "UTF-8");
    try { writer.write(report.toJson()); }
    finally { writer.close(); }
  }

  static Metrics metricsForTesting(List<Outcome> outcomes, long[] elapsedNanos)
  {
    int topOne = 0;
    int topThree = 0;
    double reciprocalRanks = 0;
    int potentialSaved = 0;
    int saved = 0;
    int corrections = 0;
    int correctCorrections = 0;
    int acceptedCorrections = 0;
    int revertedCorrections = 0;
    for (Outcome outcome : outcomes)
    {
      if (outcome.rank == 1) topOne++;
      if (outcome.rank > 0 && outcome.rank <= 3) topThree++;
      if (outcome.rank > 0) reciprocalRanks += 1.0 / outcome.rank;
      if (outcome.rank > 0) potentialSaved += outcome.potentialKeystrokesSaved;
      if (outcome.rank == 1) saved += outcome.potentialKeystrokesSaved;
      if (outcome.correctionProposed)
      {
        corrections++;
        if (outcome.correctionAccepted) correctCorrections++;
      }
      if (outcome.correctionAccepted)
      {
        acceptedCorrections++;
        if (outcome.reverted) revertedCorrections++;
      }
    }
    int total = outcomes.size();
    return new Metrics(ratio(topOne, total), ratio(topThree, total),
        total == 0 ? 0 : reciprocalRanks / total, ratio(saved, potentialSaved),
        ratio(correctCorrections, corrections), ratio(revertedCorrections, acceptedCorrections),
        percentileMillis(elapsedNanos, 0.50), percentileMillis(elapsedNanos, 0.95),
        percentileMillis(elapsedNanos, 0.99));
  }

  public static void enforceOldestDeviceLatencyGate(double p95Millis, double p99Millis)
  {
    if (p95Millis > 15.0 || p99Millis > 30.0)
      throw new AssertionError("Oldest-device prediction latency gate exceeded");
  }

  private static Metrics replay(List<Case> cases, Engine engine)
  {
    List<Outcome> outcomes = new ArrayList<Outcome>();
    long[] elapsedNanos = new long[cases.size()];
    for (int i = 0; i < cases.size(); i++)
    {
      Case benchmarkCase = cases.get(i);
      long started = System.nanoTime();
      List<PredictionCandidate> candidates = engine.predict(benchmarkCase);
      elapsedNanos[i] = System.nanoTime() - started;
      int rank = 0;
      boolean correctionProposed = false;
      for (int candidateIndex = 0; candidateIndex < candidates.size(); candidateIndex++)
      {
        PredictionCandidate candidate = candidates.get(candidateIndex);
        if (candidate.getText().equals(benchmarkCase.target))
        {
          rank = candidateIndex + 1;
          correctionProposed = candidate.getType() == CandidateType.CORRECTION;
          break;
        }
      }
      outcomes.add(new Outcome(rank,
          Math.max(0, benchmarkCase.target.codePointCount(0, benchmarkCase.target.length())
              - benchmarkCase.typed.codePointCount(0, benchmarkCase.typed.length())),
          correctionProposed, benchmarkCase.acceptedCorrection, benchmarkCase.reverted));
    }
    return metricsForTesting(outcomes, elapsedNanos);
  }

  private static double percentileMillis(long[] elapsedNanos, double percentile)
  {
    if (elapsedNanos.length == 0) return 0;
    long[] sorted = Arrays.copyOf(elapsedNanos, elapsedNanos.length);
    Arrays.sort(sorted);
    int index = (int) Math.ceil(percentile * sorted.length) - 1;
    return sorted[index] / 1_000_000.0;
  }

  private static double ratio(int numerator, int denominator)
  {
    return denominator == 0 ? 0 : (double) numerator / denominator;
  }

  private static final class DeterministicLegacyPack implements LegacyPredictionEngine.CandidateSource
  {
    @Override public LegacyPredictionEngine.Lookup find(String word, int maxResults)
    {
      return new LegacyPredictionEngine.Lookup(null, suffix(word) != null, word);
    }

    @Override public LegacyPredictionEngine.CandidateSequence suffixes(
        LegacyPredictionEngine.Lookup lookup, int maxResults)
    {
      final String result = suffix((String) lookup.token);
      return sequence(result);
    }

    @Override public LegacyPredictionEngine.CandidateSequence distance(String word,
        int maxDistance, int maxResults)
    {
      return sequence(null);
    }

    private static String suffix(String typed)
    {
      if ("hel".equals(typed)) return "hello";
      if ("wor".equals(typed)) return "world";
      if ("n".equals(typed)) return "nöd";
      return null;
    }
  }

  private static LegacyPredictionEngine.CandidateSequence sequence(final String text)
  {
    return new LegacyPredictionEngine.CandidateSequence()
    {
      @Override public int size() { return text == null ? 0 : 1; }
      @Override public String resolve(int index)
      {
        if (index != 0 || text == null) throw new IndexOutOfBoundsException();
        return text;
      }
    };
  }

  private static final class DeterministicLatinImePack implements LatinPredictionDecoder.NativeFacade
  {
    @Override public List<LatinPredictionDecoder.NativeResult> suggest(int[] input, int[] x,
        int[] y, List<PredictionRequest.KeyCenter> keyCenters, int[][] preceding,
        boolean sentenceStart, int maxResults)
    {
      String typed = new String(input, 0, input.length);
      if ("hel".equals(typed)) return nativeResults("hello", LatinPredictionDecoder.NATIVE_COMPLETION);
      if ("wor".equals(typed)) return nativeResults("world", LatinPredictionDecoder.NATIVE_COMPLETION);
      if ("n".equals(typed)) return nativeResults("nöd", LatinPredictionDecoder.NATIVE_COMPLETION);
      if (typed.isEmpty() && preceding.length > 0
          && "das".equals(new String(preceding[preceding.length - 1], 0,
              preceding[preceding.length - 1].length)))
        return nativeResults("isch", LatinPredictionDecoder.NATIVE_PREDICTION);
      return Collections.emptyList();
    }

    @Override public void close() {}

    private static List<LatinPredictionDecoder.NativeResult> nativeResults(String text, int type)
    {
      return Collections.singletonList(new LatinPredictionDecoder.NativeResult(
          text.codePoints().toArray(), 100, 100, type));
    }
  }
}
