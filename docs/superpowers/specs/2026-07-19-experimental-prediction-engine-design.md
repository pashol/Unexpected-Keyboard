# Experimental Prediction Engine Design

**Date:** 2026-07-19
**Status:** Approved
**Target:** `Unexpected-Keyboard-upstream-port`

## Goal

Add an opt-in, fully offline prediction engine that improves completions, typo
correction, next-word prediction, personalization, and two-language typing
without replacing the current engine or risking existing behavior.

Swiss German (`gsw`) is the product-quality target. English is used only as
the first integration and tooling validation pack because its reference data
and expected decoder behavior are easier to verify. Architecture and quality
decisions must favor Swiss German's non-standard spelling, dialect variation,
and personalization needs rather than optimizing only for English.

## Research Decision

Use the Apache-2.0 AOSP LatinIME decoder as the classical foundation. It
already provides compact binary dictionaries, unigram and n-gram scoring,
keyboard-proximity correction, contextual prediction, and mutable history on
Android-class hardware. Use HeliBoard as a behavioral reference for
multilingual fusion, but do not copy GPL-specific implementation where an AOSP
source is available.

Do not extend `cdict` into the new engine. Its current candidate generation and
ranking are tightly coupled, fuzzy matching is byte-oriented, and recreating a
touch-aware contextual decoder would duplicate mature LatinIME work.

Do not include neural inference in the initial experiment. Keep the request,
candidate, and ranking boundaries suitable for a future optional quantized
reranker.

The earlier experimental commit `596b55d` is a user-only bigram frequency
table, despite its neural naming. It has no base language model, smoothing,
decay, bounded storage, typo model, or cold-start predictions and is not a
suitable production foundation.

## Experiment Boundary

- Add an `Experimental prediction engine` setting, disabled by default.
- Keep the legacy `Suggestions` and `cdict` path available at all times.
- Switching the setting rebuilds the prediction engine and clears transient
  context, but does not delete either engine's learned data.
- Phase-one suggestions are explicit choices only. Automatic replacement is a
  separate later experiment.
- Typed context and personalization never leave the device. Language packs may
  be downloaded, but the prediction path must not access the network.
- Do not predict or learn in password, sensitive, or incognito editors.

## Architecture

Keep `Suggestions` as the adapter for the existing three-candidate UI and add a
model-facing boundary behind it.

### `PredictionRequest`

Contains the composing token, cursor position, up to three preceding words,
sentence state, active locales, keyboard geometry, editor flags, sensitive or
incognito state, maximum result count, and request generation.

### `PredictionCandidate`

Contains text, locale, candidate type, lexical score, context score, touch or
edit cost, personalization score, autocorrect confidence, and source metadata.
Candidate types are typed word, completion, correction, next word, and
shortcut.

### `PredictionEngine`

Provides prediction, feedback recording, session reset, and explicit cleanup.
Implementations are `LegacyPredictionEngine` and
`ExperimentalPredictionEngine`. The initial API may be synchronous under a
strict latency budget, but every request carries a generation so stale work can
be discarded if background execution is introduced.

### `LatinDecoder`

A minimal vendored AOSP Java/JNI subset that loads binary dictionaries and
generates a bounded candidate pool using trie traversal, key proximity, edit
costs, unigram probability, and up to three preceding words.

### `CandidateRanker`

Merges sources globally, deduplicates case-insensitively, applies language
weights and safety rules, preserves the typed word, and computes whether a
correction is safe enough for automatic replacement.

### `UserHistoryModel`

A bounded per-locale unigram, bigram, and trigram overlay with counts,
timestamps, decay, rejection-based unlearning, explicit deletion, and
sensitive-field suppression. Experimental metadata remains separate from the
legacy personal dictionary.

### `LanguageState`

Queries at most two active locales initially. Match quality is primary; recent
committed-word language confidence resolves close scores. Learning is written
only to the selected locale to avoid cross-language contamination.

### `LanguagePack`

Contains an immutable AOSP-format dictionary, n-gram data, locale metadata,
format version, checksum, and a source/license manifest.

The pack toolchain accepts normalized word frequencies and n-grams so a Swiss
German model can be trained without changing the Android application. It must
support `gsw` as a distinct language rather than treating dialect text as
misspelled Standard German.

## Swiss German Model Strategy

Swiss Standard German (`de-CH`) and Swiss German dialect (`gsw`) are separate
models. The `de-CH` pack follows standardized written German conventions used
in Switzerland. The `gsw` pack preserves dialect spellings and regional
variants such as `nöd`, `nid`, and `ned` rather than normalizing them into one
canonical form.

The base `gsw` pack is trained offline from corpora with documented model
training and redistribution rights. Its build pipeline:

1. Ingests UTF-8 text with source and license metadata.
2. Removes private data, markup, boilerplate, and malformed text.
3. Applies conservative Unicode and whitespace normalization without replacing
   dialect words with Standard German.
4. Counts words and sentence-bounded bigrams for the first model format.
5. Prunes and quantizes counts against held-out Swiss German text.
6. Compiles a deterministic AOSP binary dictionary and records its checksum.
7. Reports top-one/top-three recall and regional-variant coverage on held-out
   dialect fixtures.

On-device history adapts the shared model toward the user's preferred dialect
and spelling. It must refine a useful base pack, not attempt to learn Swiss
German from an empty model. Regional alternatives remain valid candidates;
personal frequency and recent context determine their order.

## Data Flow

1. `CurrentlyTypedWord` emits the composing token and bounded preceding
   context.
2. Editor privacy checks decide whether prediction and learning are permitted.
3. The configured engine queries the primary locale and optionally one
   secondary locale.
4. The native decoder produces typed-word, completion, correction, shortcut,
   and next-word candidates.
5. The ranker combines lexical probability, n-gram context, touch/edit cost,
   language confidence, and personal history.
6. Safety rules demote risky corrections involving mixed case, URLs, email
   addresses, digits, short words, and low confidence.
7. The top three candidates are adapted to the existing suggestion strip.
8. Accepted, committed, rejected, and reverted candidates update the bounded
   local history model.

The legacy engine may run in local shadow mode for comparison. Only aggregate
latency, source, acceptance, and reversion counters may be retained; candidate
or typed text must not be logged.

## Language-Pack Safety

- Download to a temporary file and verify the checksum and model format before
  activation.
- Atomically activate a valid pack and retain the previous valid version until
  activation succeeds.
- Load packs off the UI thread. Use the legacy engine until loading completes.
- Treat native errors, corrupt models, unsupported versions, timeouts, and
  memory pressure as recoverable session failures.
- On failure, disable the experimental engine for the session and immediately
  restore legacy suggestions.
- Explicitly close inactive native dictionaries rather than relying on
  finalization.
- Removing a language pack removes static model data. Learned history is
  removed only through an explicit clear action.

## Phased Builds

These are testable build/release checkpoints, not separate implementation
branches for every internal task. Work within each build may proceed
continuously with smaller test-driven commits.

### Build 1: Useful Single-Language Experiment

Deliver the engine interface, legacy adapter, opt-in setting, AOSP decoder, one
language pack, completions, proximity-aware typo candidates, static next-word
prediction, and bounded personal history together. Suggestions are displayed
for explicit selection; automatic replacement remains disabled.

Use English for the initial decoder integration pack and deterministic
reference tests. In the same build, deliver the reproducible pack compiler and
small synthetic `gsw` fixtures proving Unicode handling, regional spelling
variants, and Swiss German n-gram prediction. A corpus-trained production
`gsw` pack follows once corpus provenance and held-out evaluation data are
approved.

This intentionally combines the earlier infrastructure and prediction steps.
An engine without context and history would not be useful enough for tester
evaluation.

### Build 2: Multilingual and Hardening

Add a second active language, language confidence, globally merged candidates,
model lifecycle hardening, local legacy-versus-experimental shadow comparison,
and performance tuning. Cap active languages at two.

### Build 3: Experimental Autocorrection

Expose a separate `Experimental autocorrection` setting only after all quality,
latency, stability, privacy, and fallback thresholds pass. Never silently
enable autocorrection for a user.

### Future Build: Optional Neural Reranker

Evaluate a small quantized reranker over a classical top-K pool only after the
three classical builds are stable. It is not part of initial completion.

## Autocorrection Gate

Autocorrection remains unavailable until all of these conditions pass:

- At least 95% precision on proposed automatic corrections.
- No regression from the legacy engine on exact-word retention.
- At least 10% relative improvement in top-three recall on representative
  offline corpora.
- Fewer than 2% of autocorrections immediately reverted with backspace.
- Prediction latency below 15 ms at p95 and 30 ms at p99 on the oldest
  supported test device.
- No prediction-related crashes across at least 10,000 committed test words.
- No unbounded Java or native-memory growth across repeated sessions.
- Zero prediction or learning in password, sensitive, and incognito editors.
- A second language reduces primary-language top-three recall by no more than
  3%.
- Missing, corrupt, or failed models immediately restore legacy suggestions.

Evaluate at least 1,000 eligible correction decisions per supported language
through corpus replay and explicit tester sessions before enabling the Build 3
setting.

## Verification

- Native decoder tests cover loading, Unicode correction, proximity costs,
  n-gram scoring, corrupt packs, and explicit cleanup.
- Ranker tests cover merging, deduplication, typed-word preservation,
  confidence thresholds, language weighting, safety policy, and deterministic
  ties.
- History tests cover learning, decay, eviction, unlearning, persistence,
  locale isolation, and sensitive-field suppression.
- Integration tests cover next-word display, suggestion acceptance, backspace
  reversion, cursor edits, engine switching, pack updates, missing packs, and
  legacy fallback.
- Corpus benchmarks report top-one and top-three recall, mean reciprocal rank,
  keystrokes saved, correction precision, reversion rate, and multilingual
  regression.
- Swiss German benchmarks separately report regional-variant coverage and
  verify that personalization can reorder valid variants without deleting
  alternatives from the base model.
- Device benchmarks report cold start, p50/p95/p99 latency, peak and retained
  memory, native-memory stability, and battery cost on API 21-era and current
  devices.

## Source References

- AOSP LatinIME: https://android.googlesource.com/platform/packages/inputmethods/LatinIME/
- HeliBoard: https://github.com/HeliBorg/HeliBoard
- AOSP-compatible dictionary tooling: https://github.com/remi0s/aosp-dictionary-tools
- LiteRT, reserved for future evaluation: https://github.com/google-ai-edge/LiteRT

Engine licensing and corpus/model licensing are independent. Every language
pack must include documented provenance and redistribution rights.
