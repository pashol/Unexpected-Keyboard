# Experimental Prediction Engine Build 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver an opt-in, single-language experimental prediction engine with AOSP LatinIME completion, correction, next-word prediction, bounded local personalization, an English reference pack, and a reproducible Swiss German pack pipeline.

**Architecture:** `Suggestions` remains the UI adapter while a controller selects a behavior-compatible legacy engine or the experimental AOSP-derived engine. The decoder, ranking, context, privacy policy, history, and pack tooling have separate interfaces; failures latch the session back to the legacy engine. English validates decoder behavior, while Swiss German (`gsw`) drives pack-format, normalization, variant, and personalization requirements.

**Tech Stack:** Java 8, Android API 21+, JUnit 4, Android NDK/JNI, AOSP LatinIME `android-15.0.0_r1`, Gradle, Python 3 pack tooling.

---

## Fixed Decisions

- Pin AOSP LatinIME to commit `8081a1d8572f78488900438a6eaaec232b882bbf`.
- Keep the complete canonical native decoder compile closure initially; prune only after size and latency measurements.
- Use format-202 dictionaries for Build 1: immutable unigrams and bigrams are sufficient for completion, correction, and next-word validation.
- Use code-point offsets in prediction requests. Convert UTF-16 at Android boundaries.
- Password fields disable prediction and learning. `IME_FLAG_NO_PERSONALIZED_LEARNING` disables learning but permits the immutable model. URI/email fields permit exact completion but suppress autocorrection and history learning.
- Use resolved key-center coordinates in Build 1. Raw touch-coordinate plumbing is deferred until the decoder baseline is stable.
- Store history under `Context.getNoBackupFilesDir()`, capped at 20,000 entries and 2 MiB per locale. Use a 30-day decay half-life and flush after 50 feedback events or 30 seconds.
- Build 1 never automatically commits an experimental candidate, even when legacy `space_bar_auto_complete` is enabled.
- English is the first real integration pack. A deterministic synthetic `gsw` fixture and the complete pack builder are also Build 1 deliverables.

## File Map

**Create prediction domain and routing:**

- `srcs/juloo.keyboard2/prediction/PredictionRequest.java`
- `srcs/juloo.keyboard2/prediction/PredictionCandidate.java`
- `srcs/juloo.keyboard2/prediction/PredictionEngine.java`
- `srcs/juloo.keyboard2/prediction/PredictionFeedback.java`
- `srcs/juloo.keyboard2/prediction/ComposingContext.java`
- `srcs/juloo.keyboard2/prediction/PrecedingContextExtractor.java`
- `srcs/juloo.keyboard2/prediction/EditorPredictionPolicy.java`
- `srcs/juloo.keyboard2/prediction/LegacyPredictionEngine.java`
- `srcs/juloo.keyboard2/prediction/ExperimentalPredictionEngine.java`
- `srcs/juloo.keyboard2/prediction/PredictionEngineController.java`
- `srcs/juloo.keyboard2/prediction/PredictionEngineFactory.java`
- `srcs/juloo.keyboard2/prediction/LatinDecoder.java`
- `srcs/juloo.keyboard2/prediction/LatinPredictionDecoder.java`
- `srcs/juloo.keyboard2/prediction/CandidateRanker.java`

**Create history:**

- `srcs/juloo.keyboard2/prediction/history/UserHistoryModel.java`
- `srcs/juloo.keyboard2/prediction/history/BoundedUserHistoryModel.java`
- `srcs/juloo.keyboard2/prediction/history/UserHistoryStore.java`
- `srcs/juloo.keyboard2/prediction/history/FileUserHistoryStore.java`
- `srcs/juloo.keyboard2/prediction/history/HistoryClock.java`

**Create UI adaptation:**

- `srcs/juloo.keyboard2/suggestions/AdaptedCandidates.java`
- `srcs/juloo.keyboard2/suggestions/PredictionCandidateAdapter.java`

**Vendor decoder:**

- `vendor/latinime/NOTICE`
- `vendor/latinime/UPSTREAM.md`
- `vendor/latinime/native-sources.mk`
- `vendor/latinime/native/jni/**`
- `vendor/latinime/java/com/android/inputmethod/**`

**Create pack tooling and fixtures:**

- `tools/prediction/build_language_pack.py`
- `tools/prediction/normalize_gsw.py`
- `tools/prediction/README.md`
- `test/fixtures/latinime/minimal_en.combined`
- `test/fixtures/latinime/minimal_en.dict`
- `test/fixtures/latinime/minimal_gsw.combined`
- `test/fixtures/latinime/minimal_gsw.dict`
- `test/fixtures/latinime/manifest.json`

**Modify integration:**

- `srcs/juloo.keyboard2/CurrentlyTypedWord.java`
- `srcs/juloo.keyboard2/EditorConfig.java`
- `srcs/juloo.keyboard2/suggestions/Suggestions.java`
- `srcs/juloo.keyboard2/suggestions/CandidatesView.java`
- `srcs/juloo.keyboard2/KeyEventHandler.java`
- `srcs/juloo.keyboard2/Keyboard2.java`
- `srcs/juloo.keyboard2/Config.java`
- `res/xml/settings.xml`
- `res/values/strings.xml`
- `vendor/Android.mk`
- `build.gradle.kts`
- `proguard-rules.pro`

### Task 1: Lock the prediction contract

**Tests:** `test/juloo.keyboard2/prediction/PredictionDomainTest.java`

- [ ] Write tests proving requests cap and defensively copy three preceding words, use a positive maximum count, retain generation and code-point cursor offset, and expose immutable candidates.
- [ ] Run `./gradlew test --tests juloo.keyboard2.prediction.PredictionDomainTest`; expect compilation failure because the domain classes do not exist.
- [ ] Add the domain types. Use this engine contract:

```java
public interface PredictionEngine extends AutoCloseable {
  java.util.List<PredictionCandidate> predict(PredictionRequest request);
  void recordFeedback(PredictionFeedback feedback);
  void resetSession();
  void close();
}
```

- [ ] Define candidate types `TYPED`, `COMPLETION`, `CORRECTION`, `NEXT_WORD`, and `SHORTCUT`; feedback types `ACCEPTED`, `COMMITTED`, `REJECTED`, and `REVERTED`.
- [ ] Run the focused test and then `./gradlew test`; expect all tests to pass.
- [ ] Commit with `git commit -m "feat: define prediction engine contract"`.

### Task 2: Emit bounded composing context

**Tests:** `test/juloo.keyboard2/prediction/PrecedingContextExtractorTest.java`, `test/CurrentlyTypedWordTest.java`

- [ ] Write tests for punctuation, repeated whitespace, apostrophes, supplementary Unicode letters, a mid-token cursor, unknown/truncated editor context, four preceding words capped to three, and 100-character local context retention.
- [ ] Run the focused tests; expect failure because `ComposingContext` and `PrecedingContextExtractor` do not exist.
- [ ] Implement extraction using `Character.codePointAt` and the existing `CurrentlyTypedWord.is_word_char` rule. Never split a surrogate pair.
- [ ] Change `CurrentlyTypedWord.Callback` to receive one immutable `ComposingContext`; trim `_text_before_cursor` to `SENTENCE_CONTEXT_LENGTH` after local typing.
- [ ] Update `KeyEventHandler.currently_typed_word` to forward the snapshot without parsing text itself.
- [ ] Run focused and full tests; expect all tests to pass.
- [ ] Commit with `git commit -m "feat: expose bounded prediction context"`.

### Task 3: Enforce editor privacy policy

**Tests:** `test/juloo.keyboard2/prediction/EditorPredictionPolicyTest.java`

- [ ] Write table-driven tests for normal text, all text-password variations, number-password, `IME_FLAG_NO_PERSONALIZED_LEARNING`, URI, email, visible password, and `TYPE_TEXT_FLAG_NO_SUGGESTIONS`.
- [ ] Assert the fixed decisions: passwords deny both operations; no-personalized-learning allows static prediction only; URI/email allow exact completion only and deny learning.
- [ ] Implement `EditorPredictionPolicy` with separate `allowPrediction`, `allowCorrection`, and `allowLearning` booleans.
- [ ] Store the policy on `EditorConfig` and pass it into each request. Do not infer privacy from candidate-strip visibility.
- [ ] Run focused and full tests; expect all tests to pass.
- [ ] Commit with `git commit -m "feat: gate prediction by editor privacy"`.

### Task 4: Extract the legacy engine without changing behavior

**Tests:** `test/juloo.keyboard2/prediction/LegacyPredictionEngineTest.java`, existing `SuggestionsTest.java`

- [ ] Add parity tests for exact words, first-character case fallback, completion, distance-one correction, personal precedence, sentence capitalization, empty input, and no dictionary.
- [ ] Move current word-candidate generation from `Suggestions` into `LegacyPredictionEngine`; leave emoji lookup and presentation state in `Suggestions`.
- [ ] Map legacy results into structured candidates while retaining legacy-personal provenance.
- [ ] Add `PredictionCandidateAdapter` and `AdaptedCandidates`; preserve three word slots plus the separate emoji slot.
- [ ] Run all suggestion and prediction tests; expected candidate arrays must remain byte-for-byte identical.
- [ ] Commit with `git commit -m "refactor: isolate legacy prediction engine"`.

### Task 5: Add the opt-in controller and safe fallback

**Tests:** `test/juloo.keyboard2/prediction/PredictionEngineControllerTest.java`

- [ ] Test disabled, enabled-but-unavailable, enabled, sensitive request, experimental exception, session failure latch, next-session retry, setting change, locale change, stale generation, and exactly-once close behavior with fake engines.
- [ ] Add `experimental_prediction_engine` to `Config`, defaulting to false, plus a checkbox and warning summary under Suggestions.
- [ ] Implement the controller as the sole engine owner. On an experimental failure, return the legacy result for the same request and use legacy for the rest of the input session.
- [ ] Build/rebuild the controller in `Keyboard2`; reset it at input start/finish and close it in `onDestroy`.
- [ ] Explicitly block experimental candidates from `KeyEventHandler.handle_space_bar` automatic completion.
- [ ] Run focused and full tests; expect all tests to pass.
- [ ] Commit with `git commit -m "feat: add opt-in prediction engine routing"`.

### Task 6: Vendor the pinned LatinIME decoder

**Tests:** native build plus `test/juloo.keyboard2/prediction/LatinDecoderLifecycleTest.java`

- [ ] Fetch AOSP LatinIME commit `8081a1d8572f78488900438a6eaaec232b882bbf` into a temporary directory and verify the commit hash before copying files.
- [ ] Copy `native/jni/src/**`, the BinaryDictionary, DicTraverseSession, ProximityInfo, and JNI common bridges, preserving Apache headers.
- [ ] Record the source URL, tag, commit, license, and local modifications in `vendor/latinime/UPSTREAM.md`; add the upstream notice.
- [ ] Expand all 82 `LATIN_IME_CORE_SRC_FILES` entries from upstream `native/jni/Android.bp` into `vendor/latinime/native-sources.mk`. Assert the count in review rather than using recursive build globs.
- [ ] Reduce the Java/JNI facade to dictionary open/validate/close, traversal session, proximity geometry, typed suggestions, and empty-input predictions. Replace all finalizers with idempotent explicit `close()` methods.
- [ ] Add a `jni_latinime` module to `vendor/Android.mk` with API 21, C++14, static libc++, 16K-page support, and reproducible build-id flags.
- [ ] Add `vendor/latinime/java` to the Gradle Java sources and JNI keep rules to `proguard-rules.pro`.
- [ ] Write lifecycle tests for invalid path, corrupt header, repeated open/close, and double close.
- [ ] Run `./gradlew assembleDebug` and `./gradlew assembleRelease`; expect both native libraries in the APK and no JNI lookup failures.
- [ ] Commit with `git commit -m "feat: vendor AOSP LatinIME decoder"`.

### Task 7: Build deterministic English and Swiss German fixtures

**Tests:** `test/juloo.keyboard2/prediction/LanguagePackFixtureTest.java`, Python tool tests under `tools/prediction/test_build_language_pack.py`

- [ ] Add an English source fixture containing `hello`, `help`, `held`, `world`, `word`, `cafe`, `café`, `naive`, and `naïve`, plus `hello -> world` and `hello -> there` bigrams.
- [ ] Add a Swiss German source fixture containing regional alternatives such as `nöd`, `nid`, `ned`, `chunsch`, `chumm`, `isch`, and `guet`, plus sentence-bounded bigrams such as `das -> isch` and `isch -> guet`.
- [ ] Test that Swiss German normalization preserves all regional alternatives, `ä/ö/ü`, apostrophes, and casing rather than converting words to Standard German.
- [ ] Implement `build_language_pack.py` to accept locale, word-frequency TSV, n-gram TSV, source manifest, output path, and pinned compiler path. Sort input deterministically and reject missing license/provenance metadata.
- [ ] Generate format-202 `.dict` files and a manifest containing locale, format, source licenses, source hashes, output hash, build-tool commit, and build timestamp from `SOURCE_DATE_EPOCH`.
- [ ] Regenerate into a temporary directory and compare SHA-256 hashes with checked-in fixtures.
- [ ] Commit with `git commit -m "feat: add reproducible English and Swiss German packs"`.

### Task 8: Integrate completion, correction, and next-word decoding

**Tests:** `test/juloo.keyboard2/prediction/LatinPredictionDecoderTest.java`

- [ ] Test English prefix order for `hel`, exact retention, Unicode words, adjacent-key correction, lower score for distant geometry, and empty-input `hello -> world` prediction.
- [ ] Test Swiss German variant retention and `das -> isch` next-word prediction from the synthetic pack.
- [ ] Implement `LatinPredictionDecoder` to map code points, resolved key centers, up to three preceding words, and native result metadata into `PredictionCandidate`.
- [ ] Implement deterministic `CandidateRanker`: typed-word safety first, then context probability, lexical probability, touch/edit cost, and stable lexical tie-breaking. Apply editor policy before correction candidates are admitted.
- [ ] Use one immutable decoder instance per active pack and explicit close through the controller.
- [ ] Run focused/full tests and an API 21 emulator smoke test; expect no Unicode corruption or native leaks.
- [ ] Commit with `git commit -m "feat: add contextual experimental predictions"`.

### Task 9: Add bounded per-locale personalization

**Tests:** `test/juloo.keyboard2/prediction/history/BoundedUserHistoryModelTest.java`, `FileUserHistoryStoreTest.java`

- [ ] Test unigram/bigram/trigram counts, locale isolation, 30-day decay, 20,000-entry and 2-MiB caps, deterministic eviction, rejection/reversion decrement, explicit deletion, no-learning policy, corrupt file recovery, and atomic persistence.
- [ ] Implement immutable read snapshots and an injected `HistoryClock`; prediction must not wait for disk I/O.
- [ ] Persist a versioned file under `getNoBackupFilesDir()/prediction-history/<locale>.bin`; write temporary, fsync, and atomically rename.
- [ ] Flush after 50 feedback events or 30 seconds on a single background executor, and flush/close at service destruction.
- [ ] Interpolate history scores with the immutable model. Reorder `nöd`/`nid`/`ned` from user evidence without deleting alternatives.
- [ ] Run focused/full tests and repeated lifecycle tests; expect bounded disk and memory use.
- [ ] Commit with `git commit -m "feat: personalize predictions on device"`.

### Task 10: Plumb explicit feedback and stable candidate identity

**Tests:** `test/juloo.keyboard2/prediction/PredictionFeedbackIntegrationTest.java`

- [ ] Test accepted tap, committed typed word, rejected visible correction, immediate-backspace revert, cursor-movement cancellation, stale candidate rejection, and privacy suppression.
- [ ] Change candidate selection callbacks to carry slot and generation; resolve the full candidate from the current `AdaptedCandidates` snapshot.
- [ ] Emit feedback from `KeyEventHandler` acceptance, delimiter commit, and reversion paths without logging candidate or typed text.
- [ ] Preserve legacy personal-candidate long-press removal and emoji behavior.
- [ ] Run all tests and manually verify legacy and experimental suggestion taps.
- [ ] Commit with `git commit -m "feat: learn from prediction feedback"`.

### Task 11: Add corpus and latency comparison

**Files:** `benchmark/juloo.keyboard2/prediction/**`, `benchmark/corpora/**`, `build.gradle.kts`

- [ ] Add self-tests for top-one/top-three recall, mean reciprocal rank, keystrokes saved, correction precision, reversion rate, and p50/p95/p99 calculations.
- [ ] Add a separate `predictionBenchmark` source set/task that replays deterministic contexts against both engines and emits aggregate JSON without words or candidates.
- [ ] Include only synthetic English and Swiss German corpus cases until a redistributable real `gsw` corpus has an approved provenance manifest.
- [ ] Fail the benchmark gate when p95 exceeds 15 ms or p99 exceeds 30 ms on the designated oldest-device run; report quality metrics without enabling autocorrection.
- [ ] Run the benchmark twice and verify deterministic quality results.
- [ ] Commit with `git commit -m "test: benchmark experimental predictions"`.

### Task 12: Build 1 acceptance

- [ ] Run `./gradlew test`, `./gradlew assembleDebug`, and `./gradlew assembleRelease`; all must pass.
- [ ] Run debug APK smoke tests on API 21 and a current API: legacy default, opt-in engine, toggle fallback, password suppression, no-personalized-learning behavior, English completion/correction/next-word, and Swiss German synthetic predictions.
- [ ] Loop engine activation, prediction, and destruction while monitoring Java/native retained memory; verify no increasing handle count.
- [ ] Verify airplane-mode predictions and inspect logs to confirm no typed or candidate text is emitted.
- [ ] Verify Build 1 never automatically replaces text and legacy space-bar autocomplete remains unchanged when the experimental setting is off.
- [ ] Record APK/native-library size and benchmark output in the release notes.
- [ ] Commit with `git commit -m "test: verify prediction engine build one"`.

## Build 1 Exit Criteria

- The feature is disabled by default and legacy behavior is unchanged.
- English reference predictions pass exact, completion, proximity-correction, Unicode, and next-word fixtures.
- Swiss German tooling preserves dialect variants and the synthetic `gsw` pack predicts known bigrams.
- Personalization can favor a user's Swiss German variant without removing alternatives.
- Sensitive editors never invoke prediction or history; no-personalized-learning editors never write history.
- Experimental failures restore legacy results for the same request and latch fallback for the session.
- p95/p99 latency is reported; autocorrection remains unavailable regardless of the result.
- All JVM, native, debug, release, API 21, and current-API checks pass.

Build 2 begins only after these criteria pass. It adds a production corpus-trained `gsw` pack, second-language fusion, and hardening; Build 3 remains gated experimental autocorrection.
