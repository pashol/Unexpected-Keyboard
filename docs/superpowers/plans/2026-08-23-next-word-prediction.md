# Next-Word Prediction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an opt-in, offline next-word predictor that presents candidates after a space and commits a selected candidate followed by one space.

**Architecture:** Keep the legacy completion flow intact for non-empty composing words. Add a typed prediction boundary behind `Suggestions`, pass bounded preceding-word context from `CurrentlyTypedWord`, and route empty-token whitespace boundaries to an AOSP LatinIME-backed engine. Candidate metadata distinguishes replacement completions from append-only next-word candidates.

**Tech Stack:** Java 8 source compatibility, Android API 21+, JUnit 4, AOSP LatinIME JNI decoder, Gradle, Python 3 language-pack tooling.

---

## File Structure

- `srcs/juloo.keyboard2/prediction/`: immutable request/candidate API, policy, engine controller, and decoder facade.
- `srcs/juloo.keyboard2/suggestions/`: preserves the existing three-slot UI adapter while carrying candidate type.
- `srcs/juloo.keyboard2/CurrentlyTypedWord.java`: owns bounded editor context and determines whether a next-word request is valid.
- `srcs/juloo.keyboard2/KeyEventHandler.java`: selects replacement versus append-only candidate commit semantics.
- `vendor/latinime/`: pinned AOSP source provenance and native/Java decoder closure.
- `tools/prediction/`: deterministic builder for a fixture language pack.

### Task 1: Define the next-word domain contract

**Files:**
- Create: `srcs/juloo.keyboard2/prediction/PredictionCandidate.java`
- Create: `srcs/juloo.keyboard2/prediction/PredictionRequest.java`
- Create: `srcs/juloo.keyboard2/prediction/PredictionEngine.java`
- Test: `test/juloo.keyboard2/prediction/PredictionDomainTest.java`

- [ ] **Step 1: Write the failing domain tests**

```java
@Test public void request_caps_preceding_words_and_candidates_are_immutable() {
  PredictionRequest request = new PredictionRequest(
      Arrays.asList("one", "two", "three", "four"), 3, 7);
  assertEquals(Arrays.asList("two", "three", "four"), request.preceding_words());
  assertEquals(3, request.max_candidates());
  assertEquals(7, request.generation());
  PredictionCandidate candidate = new PredictionCandidate("world", 0.8f);
  assertEquals("world", candidate.text());
}
```

- [ ] **Step 2: Run the focused test and verify it fails because the prediction package does not exist**

Run: `./gradlew test --tests juloo.keyboard2.prediction.PredictionDomainTest`

Expected: compilation failure for missing prediction types.

- [ ] **Step 3: Add the immutable contract**

```java
public final class PredictionRequest {
  private final List<String> _preceding_words;
  private final int _max_candidates;
  private final int _generation;

  public PredictionRequest(List<String> words, int maxCandidates, int generation) {
    _preceding_words = Collections.unmodifiableList(new ArrayList<String>(
        words.subList(Math.max(0, words.size() - 3), words.size())));
    if (maxCandidates <= 0) throw new IllegalArgumentException("maxCandidates");
    _max_candidates = maxCandidates;
    _generation = generation;
  }
  public List<String> preceding_words() { return _preceding_words; }
  public int max_candidates() { return _max_candidates; }
  public int generation() { return _generation; }
}

public interface PredictionEngine extends AutoCloseable {
  List<PredictionCandidate> predict(PredictionRequest request);
  void reset_session();
  void close();
}

public final class PredictionCandidate {
  private final String _text;
  private final float _score;
  public PredictionCandidate(String text, float score) {
    if (text == null || text.length() == 0) throw new IllegalArgumentException("text");
    _text = text;
    _score = score;
  }
  public String text() { return _text; }
  public float score() { return _score; }
}
```

- [ ] **Step 4: Run focused and full unit tests**

Run: `./gradlew test --tests juloo.keyboard2.prediction.PredictionDomainTest && ./gradlew test`

Expected: both commands pass.

- [ ] **Step 5: Commit the contract**

```sh
git add srcs/juloo.keyboard2/prediction test/juloo.keyboard2/prediction
git commit -m "feat: define next-word prediction contract"
```

### Task 2: Produce only valid preceding-word context

**Files:**
- Modify: `srcs/juloo.keyboard2/CurrentlyTypedWord.java:37-43,152-156,313-316`
- Modify: `srcs/juloo.keyboard2/KeyEventHandler.java:180-183,185-190`
- Test: `test/CurrentlyTypedWordTest.java`

- [ ] **Step 1: Add failing context tests**

```java
@Test public void next_word_context_requires_empty_word_after_whitespace() {
  assertEquals(Arrays.asList("hello", "there"),
      CurrentlyTypedWord.preceding_words_for_next_word("hello there ", true, ""));
  assertTrue(CurrentlyTypedWord.preceding_words_for_next_word("hello there", true, "").isEmpty());
  assertTrue(CurrentlyTypedWord.preceding_words_for_next_word("hello ", false, "").isEmpty());
  assertTrue(CurrentlyTypedWord.preceding_words_for_next_word("hello ", true, "x").isEmpty());
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run: `./gradlew test --tests juloo.keyboard2.CurrentlyTypedWordTest`

Expected: compilation failure for `preceding_words_for_next_word`.

- [ ] **Step 3: Implement context extraction and callback transport**

Add a package-private helper that returns an empty list unless `_context_known`, the composing word is empty, and the final context code point is whitespace. Scan backward with `Character.codePointBefore` and `is_word_char`, collect at most three words, then reverse the list. Change `CurrentlyTypedWord.Callback` to receive `List<String> precedingWords`; update `KeyEventHandler` to forward that list without parsing editor text again.

```java
void callback() {
  String word = _w.toString();
  _callback.currently_typed_word(word, _sentence_start,
      preceding_words_for_next_word(_text_before_cursor, _context_known, word));
}
```

- [ ] **Step 4: Add coverage for repeated spaces, punctuation, Unicode, selection, and unknown context**

Assert repeated whitespace still returns the prior words, punctuation is skipped as a delimiter, supplementary-plane letters are retained, `set_current_word(null)` returns no words, and a selection returns no words.

- [ ] **Step 5: Run focused and full tests**

Run: `./gradlew test --tests juloo.keyboard2.CurrentlyTypedWordTest && ./gradlew test`

Expected: both commands pass.

- [ ] **Step 6: Commit context extraction**

```sh
git add srcs/juloo.keyboard2/CurrentlyTypedWord.java srcs/juloo.keyboard2/KeyEventHandler.java test/CurrentlyTypedWordTest.java
git commit -m "feat: expose next-word context"
```

### Task 3: Add policy, setting, and safe engine routing

**Files:**
- Create: `srcs/juloo.keyboard2/prediction/EditorPredictionPolicy.java`
- Create: `srcs/juloo.keyboard2/prediction/PredictionEngineController.java`
- Modify: `srcs/juloo.keyboard2/Config.java:37-81,150-208`
- Modify: `srcs/juloo.keyboard2/EditorConfig.java:39-108`
- Modify: `res/xml/settings.xml:16-24`
- Modify: `res/values/strings.xml:36-56`
- Test: `test/juloo.keyboard2/prediction/EditorPredictionPolicyTest.java`
- Test: `test/juloo.keyboard2/prediction/PredictionEngineControllerTest.java`

- [ ] **Step 1: Write failing policy and controller tests**

```java
@Test public void next_word_is_denied_for_sensitive_and_address_fields() {
  assertFalse(EditorPredictionPolicy.allow_next_word(InputType.TYPE_TEXT_VARIATION_PASSWORD));
  assertFalse(EditorPredictionPolicy.allow_next_word(InputType.TYPE_TEXT_VARIATION_URI));
  assertFalse(EditorPredictionPolicy.allow_next_word(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS));
  assertFalse(EditorPredictionPolicy.allow_next_word(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
}

@Test public void controller_latches_to_legacy_after_decoder_failure() {
  PredictionEngineController controller = new PredictionEngineController(true, failing, legacy);
  assertEquals(legacyResult, controller.predict(request));
  assertEquals(legacyResult, controller.predict(request));
}
```

- [ ] **Step 2: Run the focused tests and verify they fail**

Run: `./gradlew test --tests juloo.keyboard2.prediction.EditorPredictionPolicyTest --tests juloo.keyboard2.prediction.PredictionEngineControllerTest`

Expected: compilation failure for the new policy and controller.

- [ ] **Step 3: Implement the policy and opt-in controller**

Implement `allow_next_word(int inputType)` as false for every password variation, URI, email, non-text input, and `TYPE_TEXT_FLAG_NO_SUGGESTIONS`. Add `next_word_predictions_enabled`, default `false`, to `Config`, and add a checkbox with title `Next-word predictions` and an offline/privacy summary. The controller must use the experimental engine only when the setting and policy permit it; catch decoder exceptions, return the legacy result for that request, and use legacy for the remainder of the input session.

- [ ] **Step 4: Create and reset the controller with the input session**

Build it during `Keyboard2.onStartInputView`, call `reset_session()` when a new input starts, and call `close()` from service destruction. Do not store typed text in logs or preferences.

- [ ] **Step 5: Run focused and full tests**

Run: `./gradlew test --tests juloo.keyboard2.prediction.EditorPredictionPolicyTest --tests juloo.keyboard2.prediction.PredictionEngineControllerTest && ./gradlew test`

Expected: both commands pass.

- [ ] **Step 6: Commit policy and routing**

```sh
git add srcs/juloo.keyboard2/prediction srcs/juloo.keyboard2/Config.java srcs/juloo.keyboard2/EditorConfig.java srcs/juloo.keyboard2/Keyboard2.java res/xml/settings.xml res/values/strings.xml test/juloo.keyboard2/prediction
git commit -m "feat: add opt-in next-word prediction routing"
```

### Task 4: Preserve candidate commit semantics

**Files:**
- Modify: `srcs/juloo.keyboard2/suggestions/Suggestions.java:18-62`
- Modify: `srcs/juloo.keyboard2/suggestions/CandidatesView.java:24-80,218-227`
- Modify: `srcs/juloo.keyboard2/Config.java:336-343`
- Modify: `srcs/juloo.keyboard2/KeyEventHandler.java:157-165,400-411,609-625`
- Test: `test/juloo.keyboard2/suggestions/SuggestionsTest.java`
- Test: `test/KeyEventHandlerTest.java`

- [ ] **Step 1: Write failing tests for candidate type and append-only commit**

```java
@Test public void next_word_commit_appends_one_space_without_deleting_text() {
  FakeInputConnection connection = new FakeInputConnection("hello ");
  KeyEventHandler handler = new_handler(connection);
  handler.next_word_entered("world");
  assertEquals("hello world ", connection.text());
  assertEquals(0, connection.delete_calls);
}

@Test public void next_word_candidate_is_not_personal_or_space_autocomplete_eligible() {
  Suggestions s = new Suggestions(callback, null);
  s.set_next_word_candidates(Arrays.asList("world"));
  assertEquals(Suggestions.CandidateType.NEXT_WORD, s.types[0]);
  assertFalse(s.personal_suggestions[0]);
}
```

- [ ] **Step 2: Run focused tests and verify they fail**

Run: `./gradlew test --tests juloo.keyboard2.KeyEventHandlerTest --tests juloo.keyboard2.suggestions.SuggestionsTest`

Expected: compilation failure for `next_word_entered`, `CandidateType`, and `set_next_word_candidates`.

- [ ] **Step 3: Add typed word-slot metadata**

Add `CandidateType { COMPLETION, NEXT_WORD }` and a `CandidateType[] types` parallel to the existing word and personal arrays. `clear`, removal compaction, and completion generation must set `COMPLETION`. `set_next_word_candidates` clears word slots, fills up to three `NEXT_WORD` entries, sets every personal flag false, and leaves emoji null. Copy the type array through `CandidatesView`; click dispatch must call a new `IKeyEventHandler.candidate_entered(String, CandidateType)` method.

- [ ] **Step 4: Keep existing replacement behavior and add append-only behavior**

```java
public void candidate_entered(String text, Suggestions.CandidateType type) {
  if (type == Suggestions.CandidateType.NEXT_WORD)
    next_word_entered(text);
  else
    suggestion_entered(text);
}

void next_word_entered(String text) {
  send_text(text + " ", false);
  _next_last_action = LastAction.OTHER;
}
```

Keep stateful `complete_first`, `complete_second`, and `complete_third` on `suggestion_entered`, and require `types[0] == COMPLETION` in space-bar autocomplete. Long-press removal remains available only when a slot is personal and `COMPLETION`.

- [ ] **Step 5: Run focused and full tests**

Run: `./gradlew test --tests juloo.keyboard2.KeyEventHandlerTest --tests juloo.keyboard2.suggestions.SuggestionsTest && ./gradlew test`

Expected: existing completion replacement tests and new append-only tests pass.

- [ ] **Step 6: Commit the candidate adaptation**

```sh
git add srcs/juloo.keyboard2/suggestions/Suggestions.java srcs/juloo.keyboard2/suggestions/CandidatesView.java srcs/juloo.keyboard2/Config.java srcs/juloo.keyboard2/KeyEventHandler.java test/KeyEventHandlerTest.java test/juloo.keyboard2/suggestions/SuggestionsTest.java
git commit -m "feat: commit next-word candidates append-only"
```

### Task 5: Vendor the AOSP decoder and fixture pack

**Files:**
- Create: `vendor/latinime/NOTICE`
- Create: `vendor/latinime/UPSTREAM.md`
- Create: `vendor/latinime/native-sources.mk`
- Create: `vendor/latinime/native/jni/**`
- Create: `vendor/latinime/java/com/android/inputmethod/**`
- Create: `srcs/juloo.keyboard2/prediction/LatinDecoder.java`
- Create: `srcs/juloo.keyboard2/prediction/LatinPredictionDecoder.java`
- Create: `tools/prediction/build_language_pack.py`
- Create: `test/fixtures/latinime/minimal_en.dict`
- Modify: `vendor/Android.mk`
- Modify: `build.gradle.kts`
- Modify: `proguard-rules.pro`
- Test: `test/juloo.keyboard2/prediction/LatinDecoderLifecycleTest.java`
- Test: `test/juloo.keyboard2/prediction/LatinPredictionDecoderTest.java`

- [ ] **Step 1: Write failing lifecycle and known-bigram tests**

```java
@Test public void decoder_returns_fixture_next_word() throws Exception {
  LatinPredictionDecoder decoder = openFixture("minimal_en.dict");
  assertEquals("world", decoder.predict(new PredictionRequest(
      Arrays.asList("hello"), 3, 1)).get(0).text());
  decoder.close();
}

@Test public void decoder_close_is_idempotent() throws Exception {
  LatinPredictionDecoder decoder = openFixture("minimal_en.dict");
  decoder.close();
  decoder.close();
}
```

- [ ] **Step 2: Run the focused tests and verify they fail**

Run: `./gradlew test --tests juloo.keyboard2.prediction.LatinDecoderLifecycleTest --tests juloo.keyboard2.prediction.LatinPredictionDecoderTest`

Expected: compilation failure because the LatinIME facade and fixture pack do not exist.

- [ ] **Step 3: Vendor and document the exact upstream closure**

Fetch AOSP LatinIME commit `8081a1d8572f78488900438a6eaaec232b882bbf` into a temporary directory, verify the hash, and copy the native dictionary traversal/JNI closure and required Java classes while preserving Apache headers. Record upstream URL, commit, license, copied paths, and local changes in `UPSTREAM.md`. List source files explicitly in `native-sources.mk`; do not use recursive source globs.

- [ ] **Step 4: Expose the minimal decoder API**

```java
public interface LatinDecoder extends AutoCloseable {
  List<PredictionCandidate> next_words(List<String> precedingWords, int maxCandidates);
  void close();
}
```

The JNI facade loads one immutable dictionary, requests next-word candidates from up to three preceding words, bounds output to `maxCandidates`, maps native results to immutable candidates, and implements explicit idempotent close. Reject missing or corrupt dictionaries before creating a traversal session.

- [ ] **Step 5: Add deterministic English fixture-pack generation**

Implement `build_language_pack.py` to accept a locale, frequency TSV, bigram TSV, and output path; sort all input, reject missing files, and compile the fixture containing `hello -> world` and `hello -> there`. Record input/output SHA-256 values in a JSON manifest. The fixture is test data only and requires no network at runtime.

- [ ] **Step 6: Integrate native build and run verification**

Add the JNI library to `vendor/Android.mk`, include vendored Java sources in `build.gradle.kts`, and keep JNI symbols in ProGuard rules. Run:

```sh
./gradlew test --tests juloo.keyboard2.prediction.LatinDecoderLifecycleTest --tests juloo.keyboard2.prediction.LatinPredictionDecoderTest
./gradlew assembleDebug
./gradlew assembleRelease
```

Expected: lifecycle and fixture tests pass; both APK variants contain the JNI library and build successfully.

- [ ] **Step 7: Commit the decoder foundation**

```sh
git add vendor/latinime vendor/Android.mk srcs/juloo.keyboard2/prediction tools/prediction test/fixtures/latinime test/juloo.keyboard2/prediction build.gradle.kts proguard-rules.pro
git commit -m "feat: add offline next-word decoder"
```

### Task 6: Connect decoder results to the candidate strip

**Files:**
- Modify: `srcs/juloo.keyboard2/suggestions/Suggestions.java:35-51`
- Modify: `srcs/juloo.keyboard2/KeyEventHandler.java:180-190`
- Modify: `srcs/juloo.keyboard2/Keyboard2.java:254-263,351-357,533-552`
- Test: `test/juloo.keyboard2/suggestions/SuggestionsTest.java`
- Test: `test/KeyEventHandlerTest.java`

- [ ] **Step 1: Write the failing integration tests**

```java
@Test public void whitespace_boundary_displays_next_word_candidates() {
  Suggestions s = suggestions_with_engine(words("hello"), words("world", "there"));
  s.currently_typed_word("", false, words("hello"));
  assertArrayEquals(new String[] { "world", "there", null }, s.suggestions);
  assertEquals(Suggestions.CandidateType.NEXT_WORD, s.types[0]);
}

@Test public void nonempty_word_keeps_legacy_completion_path() {
  Suggestions s = suggestions_with_engine(words("hello"), words("world"));
  s.currently_typed_word("hel", false, words("hello"));
  assertEquals(Suggestions.CandidateType.COMPLETION, s.types[0]);
}
```

- [ ] **Step 2: Run the focused tests and verify they fail**

Run: `./gradlew test --tests juloo.keyboard2.suggestions.SuggestionsTest --tests juloo.keyboard2.KeyEventHandlerTest`

Expected: assertions fail because `Suggestions` does not route empty-token context to the controller.

- [ ] **Step 3: Implement boundary routing**

Give `Suggestions` a `PredictionEngineController`. If the word is non-empty, retain `query_suggestions` exactly. If the word is empty and preceding words are non-empty, ask the controller for up to `MAX_COUNT` candidates and call `set_next_word_candidates`. Otherwise clear only word candidates. Increment a request generation whenever `CurrentlyTypedWord` refreshes or selection changes, and discard any response whose generation is no longer current.

- [ ] **Step 4: Enforce candidate clearing and stateful-key compatibility**

On cursor movement, selection, deletion, and non-whitespace text, clear `NEXT_WORD` candidates before publishing the strip. Keep emoji and all stateful completion keys bound to legacy completion candidates only.

- [ ] **Step 5: Run focused tests, full tests, and APK builds**

Run: `./gradlew test --tests juloo.keyboard2.suggestions.SuggestionsTest --tests juloo.keyboard2.KeyEventHandlerTest && ./gradlew test && ./gradlew assembleDebug && ./gradlew assembleRelease`

Expected: all commands pass.

- [ ] **Step 6: Commit end-to-end next-word display**

```sh
git add srcs/juloo.keyboard2/suggestions/Suggestions.java srcs/juloo.keyboard2/KeyEventHandler.java srcs/juloo.keyboard2/Keyboard2.java test/juloo.keyboard2/suggestions/SuggestionsTest.java test/KeyEventHandlerTest.java
git commit -m "feat: display offline next-word predictions"
```

### Task 7: Verify device behavior and privacy boundary

**Files:**
- Modify: `docs/superpowers/specs/2026-08-23-next-word-prediction-design.md`

- [ ] **Step 1: Build an installable debug APK**

Run: `./gradlew assembleDebug`

Expected: `build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 2: Smoke-test normal text behavior**

Install with `./gradlew installDebug`. In a normal text field, type `hello ` and verify fixture candidates include `world`; tap it and verify the editor becomes `hello world `. Type a character and move the cursor, then verify next-word candidates disappear.

- [ ] **Step 3: Smoke-test excluded fields and fallback**

Verify no next-word candidates in password, URI, email, and no-suggestions fields. Rename or corrupt the fixture pack, start a new input session, and verify the legacy completion strip remains usable with no crash.

- [ ] **Step 4: Record verification and commit**

Add the tested API level, device, fixture-pack hash, and pass/fail observations to the design document.

```sh
git add docs/superpowers/specs/2026-08-23-next-word-prediction-design.md
git commit -m "test: verify next-word prediction behavior"
```
