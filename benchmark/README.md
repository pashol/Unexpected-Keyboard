# Prediction Benchmark

`./gradlew predictionBenchmark` replays deterministic synthetic English and
Swiss German contexts through the legacy and experimental replay engines. It
writes and prints `build/reports/prediction-benchmark.json`; that report has
aggregate quality and latency metrics only, never corpus words or candidates.

Run the task twice and compare the `qualityJson` fields in the emitted report.
Latency is measured on every host, but the `p95 <= 15 ms` and `p99 <= 30 ms`
gate is enabled only for the designated oldest-device run:

```
./gradlew predictionBenchmark -PpredictionBenchmarkOldestDevice=true
```

The corpus directory deliberately contains only synthetic, redistributable
`en` and `gsw-CH` TSV cases. Autocorrection is never enabled by the benchmark.
