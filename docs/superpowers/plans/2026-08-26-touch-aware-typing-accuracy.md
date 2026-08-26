# Touch-Aware Typing Accuracy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve explicit current-word suggestions for neighboring-key tap errors by combining literal text, fixed keyboard geometry, tap coordinates, and the existing offline LatinIME language packs.

**Architecture:** Preserve literal key commitment and the legacy `cdict` path. Add immutable touch and geometry inputs to the prediction domain, track only complete direct-tap sequences, and route eligible current words through LatinIME's already-compiled proximity decoder. Invalid touch evidence falls back to text-only LatinIME; decoder or pack failure falls back to legacy suggestions for the session.

**Tech Stack:** Java 8, Android API 21+, JUnit 4, AndroidX instrumentation, AOSP LatinIME Java/JNI, Gradle, Python 3 benchmark validation.

---

## File Structure

- Create `srcs/juloo.keyboard2/prediction/TouchSequence.java`: immutable literal code points, normalized coordinates, and geometry generation.
- Create `srcs/juloo.keyboard2/prediction/KeyboardProximity.java`: immutable decoder-ready geometry arrays and generation.
- Create `srcs/juloo.keyboard2/prediction/KeyboardProximityBuilder.java`: pure conversion from logical key rectangles to the bounded native grid.
- Modify `srcs/juloo.keyboard2/prediction/PredictionRequest.java`: named current-word and next-word factories with request-kind validation.
- Modify `srcs/juloo.keyboard2/Keyboard2View.java` and `srcs/juloo.keyboard2/Pointers.java`: build geometry snapshots and propagate direct-tap metadata without changing hit testing.
- Modify `srcs/juloo.keyboard2/CurrentlyTypedWord.java` and `srcs/juloo.keyboard2/KeyEventHandler.java`: record evidence only after successful commit and invalidate uncertain sequences.
- Modify `srcs/juloo.keyboard2/suggestions/Suggestions.java`: route non-empty words through experimental prediction while preserving strict literal-word retention.
- Modify `vendor/latinime/java/com/android/inputmethod/keyboard/ProximityInfo.java` and `vendor/latinime/java/com/android/inputmethod/latin/BinaryDictionary.java`: expose the existing native proximity and typed-suggestion entrypoints.
- Modify `srcs/juloo.keyboard2/prediction/LatinimeDictionary.java`: locale-correct typed decoding and native proximity lifecycle.
- Add `androidTest/assets/latinime/touch_accuracy_cases.tsv` and instrumentation coverage: deterministic four-locale accuracy, latency, privacy, and lifecycle checks.

Do not change the JNI descriptors, C++ source list, raw key hit testing, settings UI, or candidate-strip layout in this plan.

### Task 1: Define Immutable Touch-Aware Prediction Inputs

**Files:**
- Create: `srcs/juloo.keyboard2/prediction/TouchSequence.java`
- Create: `srcs/juloo.keyboard2/prediction/KeyboardProximity.java`
- Modify: `srcs/juloo.keyboard2/prediction/PredictionRequest.java`
- Create: `test/juloo.keyboard2/prediction/TouchSequenceTest.java`
- Modify: `test/juloo.keyboard2/prediction/PredictionDomainTest.java`

- [ ] **Step 1: Write failing immutability and request-kind tests**

```java
@Test public void touch_sequence_is_defensive_and_requires_matching_lengths() {
  int[] codePoints = { 'g', 'e' };
  int[] xs = { 120, 180 };
  int[] ys = { 80, 80 };
  TouchSequence sequence = new TouchSequence(codePoints, xs, ys, 4);
  codePoints[0] = 'x';
  xs[0] = 0;
  assertArrayEquals(new int[] { 'g', 'e' }, sequence.code_points());
  assertArrayEquals(new int[] { 120, 180 }, sequence.x_coordinates());
  assertEquals(4, sequence.geometry_generation());
}

@Test public void current_word_request_validates_touch_alignment() {
  TouchSequence sequence = new TouchSequence(
      new int[] { 'g', 'e' }, new int[] { 10, 20 }, new int[] { 10, 10 }, 2);
  PredictionRequest request = PredictionRequest.current_word(
      "ge", 2, Arrays.asList("say"), 3, 7, sequence, proximity(2));
  assertEquals(PredictionRequest.Kind.CURRENT_WORD, request.kind());
  assertEquals("ge", request.composing_text());
  assertEquals(2, request.cursor_code_point());
}

@Test public void next_word_request_has_no_composing_payload() {
  PredictionRequest request = PredictionRequest.next_word(
      Arrays.asList("hello"), 3, 8);
  assertEquals(PredictionRequest.Kind.NEXT_WORD, request.kind());
  assertEquals("", request.composing_text());
  assertNull(request.touch_sequence());
}
```

- [ ] **Step 2: Run the focused tests and verify they fail for missing APIs**

Run: `./gradlew testDebugUnitTest --tests 'juloo.keyboard2.prediction.TouchSequenceTest' --tests 'juloo.keyboard2.prediction.PredictionDomainTest'`

Expected: compilation fails because `TouchSequence`, `Kind`, and named request factories do not exist.

- [ ] **Step 3: Implement `TouchSequence` and the geometry value object**

`TouchSequence` must reject empty arrays, mismatched lengths, negative generations, and return defensive copies. `KeyboardProximity` must defensively own display/grid dimensions, key bounds, character codes, sweet spots, and the `gridWidth * gridHeight * 16` proximity array. Expose package-stable getters required by the LatinIME adapter.

```java
public final class TouchSequence {
  private final int[] _code_points, _xs, _ys;
  private final int _geometry_generation;

  public TouchSequence(int[] codePoints, int[] xs, int[] ys, int generation) {
    if (codePoints == null || codePoints.length == 0 || xs == null || ys == null
        || codePoints.length != xs.length || codePoints.length != ys.length
        || generation < 0)
      throw new IllegalArgumentException("touch sequence");
    _code_points = codePoints.clone();
    _xs = xs.clone();
    _ys = ys.clone();
    _geometry_generation = generation;
  }
  public int[] code_points() { return _code_points.clone(); }
  public int[] x_coordinates() { return _xs.clone(); }
  public int[] y_coordinates() { return _ys.clone(); }
  public int size() { return _code_points.length; }
  public int geometry_generation() { return _geometry_generation; }
}
```

- [ ] **Step 4: Replace positional request construction with validated factories**

Add `Kind { CURRENT_WORD, NEXT_WORD }`, keep at most three preceding words, convert the composing token to code points for exact touch alignment, validate `0 <= cursorCodePoint <= tokenCodePointCount`, require proximity whenever touch evidence is present, and require matching geometry generations. The test helper `proximity(int generation)` constructs a one-key `KeyboardProximity` with valid dimensions and arrays. Update all existing call sites and tests to use `PredictionRequest.next_word(...)`.

- [ ] **Step 5: Run focused and full JVM tests**

Run: `./gradlew testDebugUnitTest --tests 'juloo.keyboard2.prediction.TouchSequenceTest' --tests 'juloo.keyboard2.prediction.PredictionDomainTest' && ./gradlew testDebugUnitTest`

Expected: all selected tests and the full JVM suite pass.

- [ ] **Step 6: Commit the domain contract**

```bash
git add srcs/juloo.keyboard2/prediction/TouchSequence.java srcs/juloo.keyboard2/prediction/KeyboardProximity.java srcs/juloo.keyboard2/prediction/PredictionRequest.java test/juloo.keyboard2/prediction/TouchSequenceTest.java test/juloo.keyboard2/prediction/PredictionDomainTest.java
git commit -m "feat: define touch-aware prediction inputs"
```

### Task 2: Build Decoder Geometry From Logical Key Bounds

**Files:**
- Create: `srcs/juloo.keyboard2/prediction/KeyboardProximityBuilder.java`
- Create: `test/juloo.keyboard2/prediction/KeyboardProximityBuilderTest.java`
- Modify: `srcs/juloo.keyboard2/Keyboard2View.java`

- [ ] **Step 1: Write failing pure-Java geometry tests**

```java
@Test public void builder_preserves_shift_width_and_row_offsets() {
  KeyboardProximityBuilder builder = new KeyboardProximityBuilder(300, 120, 5);
  builder.add_key('q', 0, 0, 60, 60);
  builder.add_key('w', 75, 0, 45, 60);
  builder.add_key('a', 30, 60, 60, 60);
  KeyboardProximity result = builder.build();
  assertArrayEquals(new int[] { 0, 75, 30 }, result.key_x_coordinates());
  assertArrayEquals(new int[] { 60, 45, 60 }, result.key_widths());
  assertEquals(5, result.generation());
  assertEquals(result.grid_width() * result.grid_height() * 16,
      result.proximity_chars().length);
}

@Test(expected = IllegalArgumentException.class)
public void builder_rejects_more_than_native_key_limit() {
  KeyboardProximityBuilder builder = new KeyboardProximityBuilder(1000, 100, 1);
  for (int i = 0; i < 65; ++i)
    builder.add_key('a' + i, i * 10, 0, 10, 10);
  builder.build();
}
```

- [ ] **Step 2: Run the test and verify it fails because the builder is absent**

Run: `./gradlew testDebugUnitTest --tests 'juloo.keyboard2.prediction.KeyboardProximityBuilderTest'`

Expected: compilation failure for `KeyboardProximityBuilder`.

- [ ] **Step 3: Implement the bounded grid builder**

Use logical bounds, not painted key margins. Include only `KeyValue.Kind.Char` center values, normalize uppercase character codes to lowercase for decoder lookup, cap native keys at 64, and fill each grid cell with intersecting/nearest key characters followed by `0` padding to 16 entries. Use key centers for sweet-spot X/Y and `min(width,height) * 0.5f` for radii. Reject duplicate main-character code points rather than merging distant rectangles; the split-layout test in Step 4 decides whether native support must be extended.

- [ ] **Step 4: Add view integration and geometry-generation tests**

In `Keyboard2View`, rebuild the snapshot after `onMeasure` whenever `_keyboard`, `_keyWidth`, `_tc.row_height`, margins, insets, or measured dimensions differ. Increment one monotonic generation only on an actual geometry change. Iterate rows exactly like hit testing: start Y at `_config.marginTop`, add `row.shift * rowHeight`, start X at `_marginLeft`, add `key.shift * keyWidth`, and use `key.width * keyWidth` by `row.height * rowHeight`.

Add tests for portrait/landscape dimensions, variable widths, shifted rows, and a representative split layout. If a supported split layout duplicates a main character, stop this task and add a narrowly scoped native multi-index lookup test before altering `proximity_info.cpp`; do not union distant rectangles.

- [ ] **Step 5: Run geometry tests and assemble the debug APK**

Run: `./gradlew testDebugUnitTest --tests 'juloo.keyboard2.prediction.KeyboardProximityBuilderTest' && ./gradlew assembleDebug`

Expected: geometry tests pass and `assembleDebug` succeeds.

- [ ] **Step 6: Commit geometry generation**

```bash
git add srcs/juloo.keyboard2/prediction/KeyboardProximityBuilder.java srcs/juloo.keyboard2/Keyboard2View.java test/juloo.keyboard2/prediction/KeyboardProximityBuilderTest.java
git commit -m "feat: build keyboard proximity geometry"
```

### Task 3: Expose LatinIME Typed-Input And Proximity Facades

**Files:**
- Modify: `vendor/latinime/java/com/android/inputmethod/keyboard/ProximityInfo.java`
- Modify: `vendor/latinime/java/com/android/inputmethod/latin/BinaryDictionary.java`
- Modify: `srcs/juloo.keyboard2/prediction/ProductionPredictionPack.java`
- Modify: `srcs/juloo.keyboard2/prediction/LatinimeDictionary.java`
- Modify: `srcs/juloo.keyboard2/Keyboard2.java`
- Modify: `test/juloo.keyboard2/prediction/LatinimeDictionaryTest.java`
- Modify: `test/juloo.keyboard2/prediction/NativeDecoderLifecycleTest.java`

- [ ] **Step 1: Write failing locale and lifecycle tests**

```java
@Test public void production_pack_exposes_selected_locale() throws Exception {
  ProductionPredictionPack pack = ProductionPredictionPack.select(registry, "de-CH");
  assertEquals("de-CH", pack.locale());
}

@Test public void dictionary_dispatches_request_kind() {
  assertEquals(PredictionRequest.Kind.CURRENT_WORD, currentRequest.kind());
  assertEquals(PredictionRequest.Kind.NEXT_WORD, nextRequest.kind());
}
```

Add a lifecycle seam to count proximity releases, then assert dictionary close releases every cached proximity exactly once before closing the traversal session and dictionary.

- [ ] **Step 2: Run focused tests and verify missing locale/typed APIs fail**

Run: `./gradlew testDebugUnitTest --tests 'juloo.keyboard2.prediction.LatinimeDictionaryTest' --tests 'juloo.keyboard2.prediction.NativeDecoderLifecycleTest'`

Expected: compilation failure for `ProductionPredictionPack.locale()` and current-word dispatch seams.

- [ ] **Step 3: Make `ProximityInfo` constructible and safely closable**

Add a public constructor taking `KeyboardProximity` arrays or equivalent primitive parameters, assign the result of `setProximityInfoNative`, throw when it returns zero, expose a `native_handle()` getter to `BinaryDictionary`, and retain idempotent `close()`. Do not alter the native method descriptor.

- [ ] **Step 4: Add a typed-input wrapper to `BinaryDictionary`**

The wrapper must allocate native-required arrays (`18 * 48` output code points and 18 scores/types/indices), pass all-zero pointer IDs and times, set `IS_GESTURE` false, set `USE_FULL_EDIT_DISTANCE` true, use `-1` coordinates for text-only requests, and pass the native proximity handle only for complete touch requests.

```java
public static void get_typed_suggestions(long dictionary, long proximity, long session,
    int[] xs, int[] ys, int[] input, int[][] precedingWords, int[] count,
    int[] outputCodePoints, int[] outputScores) {
  int[] options = new int[5];
  options[1] = 1;
  getSuggestionsNative(dictionary, proximity, session, xs, ys, new int[input.length],
      new int[input.length], input, input.length, options, precedingWords,
      new boolean[precedingWords.length], precedingWords.length, count,
      outputCodePoints, outputScores, new int[18], new int[18], new int[18],
      new float[] { 1.0f });
}
```

- [ ] **Step 5: Make dictionary sessions locale-correct and dispatch by kind**

Add `ProductionPredictionPack.locale()`, change `LatinimeDictionary.open(File)` to `open(File, String locale)`, validate via a temporary locale-correct session, and update `Keyboard2.create_prediction_controller(...)`. Keep `next_words(...)` behavior unchanged. For current-word requests, reject tokens of 48 or more code points, create/cache one native `ProximityInfo` per active geometry generation, call the typed wrapper, sort by descending score with candidate text as a stable secondary key, and close cached proximity handles in `close()`.

- [ ] **Step 6: Run unit tests and native packaging verification**

Run: `./gradlew testDebugUnitTest --tests 'juloo.keyboard2.prediction.LatinimeDictionaryTest' --tests 'juloo.keyboard2.prediction.NativeDecoderLifecycleTest' && ./gradlew assembleDebug assembleDebugAndroidTest && tools/verify_latinime_native.sh`

Expected: tests pass, both APKs assemble, and the native verification script succeeds without a JNI descriptor change.

- [ ] **Step 7: Commit the decoder facade**

```bash
git add vendor/latinime/java/com/android/inputmethod/keyboard/ProximityInfo.java vendor/latinime/java/com/android/inputmethod/latin/BinaryDictionary.java srcs/juloo.keyboard2/prediction/ProductionPredictionPack.java srcs/juloo.keyboard2/prediction/LatinimeDictionary.java srcs/juloo.keyboard2/Keyboard2.java test/juloo.keyboard2/prediction/LatinimeDictionaryTest.java test/juloo.keyboard2/prediction/NativeDecoderLifecycleTest.java
git commit -m "feat: expose touch-aware LatinIME decoding"
```

### Task 4: Propagate Only Eligible Direct-Tap Metadata

**Files:**
- Modify: `srcs/juloo.keyboard2/Pointers.java`
- Modify: `srcs/juloo.keyboard2/Keyboard2View.java`
- Modify: `srcs/juloo.keyboard2/Config.java`
- Modify: `srcs/juloo.keyboard2/KeyEventHandler.java`
- Modify: `srcs/juloo.keyboard2/EmojiGridView.java`
- Modify: `test/KeyEventHandlerTest.java`
- Create: `test/juloo.keyboard2/PointersTest.java`

- [ ] **Step 1: Write failing pointer-origin tests**

```java
@Test public void center_tap_reports_original_key_point_and_generation() {
  pointers.onTouchDown(12, 34, 7, letterKey);
  pointers.onTouchUp(7);
  assertTrue(handler.lastTouch.direct_main_tap());
  assertEquals(12f, handler.lastTouch.x(), 0f);
  assertEquals(34f, handler.lastTouch.y(), 0f);
}

@Test public void swipe_and_long_press_are_not_direct_taps() {
  assertFalse(run_side_swipe().direct_main_tap());
  assertFalse(run_long_press().direct_main_tap());
}
```

- [ ] **Step 2: Run tests and verify the callback signature fails**

Run: `./gradlew testDebugUnitTest --tests 'juloo.keyboard2.PointersTest' --tests 'juloo.keyboard2.KeyEventHandlerTest'`

Expected: compilation failure because pointer-up callbacks carry no touch origin.

- [ ] **Step 3: Add one immutable `DirectTap` callback payload**

Define `DirectTap.NONE` plus selected center-key code point, down X/Y, geometry generation, and eligibility. Store eligibility in `Pointer`; invalidate it when value changes through swipe/circle/roundtrip/slider, long press/repeat, or modifier transformation changes the output away from the original one-code-point center character. Pass `NONE` for emoji, programmatic, fake, and non-view key-up call sites.

- [ ] **Step 4: Thread the payload without changing key commitment**

Extend `IPointerEventHandler.onPointerUp`, `Config.IKeyEventHandler.key_up`, `Keyboard2View.onPointerUp`, and `KeyEventHandler.key_up`. Preserve `key_down` and all existing gesture behavior. At this task boundary, `KeyEventHandler` accepts but does not yet retain the payload.

- [ ] **Step 5: Run pointer/input tests and the full JVM suite**

Run: `./gradlew testDebugUnitTest --tests 'juloo.keyboard2.PointersTest' --tests 'juloo.keyboard2.KeyEventHandlerTest' && ./gradlew testDebugUnitTest`

Expected: direct taps retain metadata, all transformed inputs produce `NONE`, and existing tests pass.

- [ ] **Step 6: Commit touch-origin propagation**

```bash
git add srcs/juloo.keyboard2/Pointers.java srcs/juloo.keyboard2/Keyboard2View.java srcs/juloo.keyboard2/Config.java srcs/juloo.keyboard2/KeyEventHandler.java srcs/juloo.keyboard2/EmojiGridView.java test/KeyEventHandlerTest.java test/juloo.keyboard2/PointersTest.java
git commit -m "feat: propagate direct tap metadata"
```

### Task 5: Track Complete Touch Sequences After Successful Commits

**Files:**
- Modify: `srcs/juloo.keyboard2/CurrentlyTypedWord.java`
- Modify: `srcs/juloo.keyboard2/KeyEventHandler.java`
- Modify: `test/CurrentlyTypedWordTest.java`
- Modify: `test/KeyEventHandlerTest.java`

- [ ] **Step 1: Write failing commit-order and invalidation tests**

```java
@Test public void successful_commit_records_touch_before_prediction_callback() {
  handler.key_up(letterG, Modifiers.EMPTY, tap('g', 10, 20, 3));
  assertEquals(Arrays.asList("commit:g", "predict:g"), inputConnection.events);
  assertEquals(1, callback.lastTouchSequence.size());
}

@Test public void failed_commit_does_not_record_or_predict_the_character() {
  inputConnection.commitResult = false;
  handler.key_up(letterG, Modifiers.EMPTY, tap('g', 10, 20, 3));
  assertNull(callback.lastTouchSequence);
}

@Test public void cursor_move_and_external_edit_invalidate_touch_evidence() {
  typedWord.typed("g", tap('g', 10, 20, 3));
  typedWord.selection_updated(1, 0, 0);
  assertNull(callback.lastTouchSequence);
}
```

- [ ] **Step 2: Run focused tests and verify ordering/evidence assertions fail**

Run: `./gradlew testDebugUnitTest --tests 'juloo.keyboard2.CurrentlyTypedWordTest' --tests 'juloo.keyboard2.KeyEventHandlerTest'`

Expected: tests fail because commit occurs after callback and no sequence is tracked.

- [ ] **Step 3: Reorder direct text commitment safely**

For text committed through `send_text`, call `InputConnection.commitText(...)` first. Only when it returns true call the appropriate `CurrentlyTypedWord.typed(text, directTap)` and therefore prediction callback. Keep event-key handling unchanged. Add regression assertions for composition, modifiers, macros, and space-bar behavior.

- [ ] **Step 4: Add composing evidence and current-word context**

Maintain mutable code-point/X/Y arrays internally, but expose only an immutable `TouchSequence` when every code point in the current token aligns exactly and uses one geometry generation. Add `preceding_words_for_current_word(context, contextKnown, composingWord)` that removes the composing suffix from known context, scans backward with `is_word_char`, and returns at most three completed words. Publish token, sentence state, current-word context, code-point cursor, optional touch sequence, and proximity snapshot through the callback.

- [ ] **Step 5: Implement conservative invalidation**

Remove the last sample only for an unambiguous end-of-token single-code-point backspace. Invalidate on cursor movement, selections, candidate replacement, uncertain deletion, editor refresh mismatch, pasted/string/macro input, gesture output, editor restart, geometry generation change, and external text changes. Delimiter commits clear the completed token sequence before next-word prediction.

- [ ] **Step 6: Run focused and full JVM tests**

Run: `./gradlew testDebugUnitTest --tests 'juloo.keyboard2.CurrentlyTypedWordTest' --tests 'juloo.keyboard2.KeyEventHandlerTest' && ./gradlew testDebugUnitTest`

Expected: ordering, context, Unicode code-point cursor, backspace, and invalidation tests pass with no legacy regression.

- [ ] **Step 7: Commit composing touch tracking**

```bash
git add srcs/juloo.keyboard2/CurrentlyTypedWord.java srcs/juloo.keyboard2/KeyEventHandler.java test/CurrentlyTypedWordTest.java test/KeyEventHandlerTest.java
git commit -m "feat: track composing touch sequences"
```

### Task 6: Route Current Words And Preserve Literal Candidates

**Files:**
- Modify: `srcs/juloo.keyboard2/suggestions/Suggestions.java`
- Modify: `srcs/juloo.keyboard2/KeyEventHandler.java`
- Modify: `test/juloo.keyboard2/suggestions/SuggestionsTest.java`
- Modify: `test/juloo.keyboard2/prediction/PredictionEngineControllerTest.java`

- [ ] **Step 1: Write failing routing and retention tests**

```java
@Test public void eligible_current_word_uses_experimental_candidates() {
  suggestions.currently_typed_word(state("gello", touchSequence));
  assertArrayEquals(new String[] { "gello", "hello", null }, suggestions.suggestions);
  assertEquals(Suggestions.CandidateType.COMPLETION, suggestions.types[1]);
}

@Test public void current_word_without_touch_uses_text_only_request() {
  suggestions.currently_typed_word(state("gello", null));
  assertNull(engine.lastRequest.touch_sequence());
  assertEquals(PredictionRequest.Kind.CURRENT_WORD, engine.lastRequest.kind());
}

@Test public void empty_word_keeps_next_word_routing() {
  suggestions.currently_typed_word(state_after_space("hello"));
  assertEquals(PredictionRequest.Kind.NEXT_WORD, engine.lastRequest.kind());
}
```

- [ ] **Step 2: Run focused tests and verify current words still use `cdict`**

Run: `./gradlew testDebugUnitTest --tests 'juloo.keyboard2.suggestions.SuggestionsTest' --tests 'juloo.keyboard2.prediction.PredictionEngineControllerTest'`

Expected: routing tests fail because non-empty words do not invoke the controller.

- [ ] **Step 3: Add current-word request routing**

For composing tokens of at least two code points, issue `PredictionRequest.current_word(...)` when the controller is present. Preserve the existing empty-token next-word branch. If the controller is absent, disabled, session-latched to an empty legacy engine, throws, or yields no usable candidates, call the existing `query_suggestions(...)` unchanged.

- [ ] **Step 4: Add strict literal retention and deterministic adaptation**

Deduplicate case-insensitively, apply existing sentence capitalization, retain native order, and force the literal token into slot zero while keeping up to two highest-ranked nonliteral candidates. Keep all types `COMPLETION`, all personal flags false, and preserve the separate emoji slot. Tapping a candidate uses existing replacement; pressing space never chooses a native candidate automatically.

- [ ] **Step 5: Test stale request and geometry generations**

Increment `_request_generation` for every update. Discard results whose request generation is stale or whose touch/proximity geometry generation no longer matches the current snapshot. Verify a stale result cannot overwrite next-word or newer current-word candidates.

- [ ] **Step 6: Run routing tests and full JVM suite**

Run: `./gradlew testDebugUnitTest --tests 'juloo.keyboard2.suggestions.SuggestionsTest' --tests 'juloo.keyboard2.prediction.PredictionEngineControllerTest' && ./gradlew testDebugUnitTest`

Expected: current-word, text-only, next-word, literal-retention, fallback, and stale-result tests pass.

- [ ] **Step 7: Commit suggestion routing**

```bash
git add srcs/juloo.keyboard2/suggestions/Suggestions.java srcs/juloo.keyboard2/KeyEventHandler.java test/juloo.keyboard2/suggestions/SuggestionsTest.java test/juloo.keyboard2/prediction/PredictionEngineControllerTest.java
git commit -m "feat: rank current words with tap proximity"
```

### Task 7: Enforce Privacy And Session Boundaries

**Files:**
- Modify: `srcs/juloo.keyboard2/prediction/EditorPredictionPolicy.java`
- Modify: `srcs/juloo.keyboard2/Keyboard2.java`
- Modify: `srcs/juloo.keyboard2/KeyEventHandler.java`
- Modify: `test/juloo.keyboard2/prediction/EditorPredictionPolicyTest.java`
- Modify: `test/juloo.keyboard2/prediction/PredictionSessionControllerTest.java`
- Modify: `test/KeyEventHandlerTest.java`

- [ ] **Step 1: Write failing privacy tests**

```java
@Test public void one_policy_excludes_sensitive_and_address_editors() {
  assertFalse(EditorPredictionPolicy.allow_prediction(TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD));
  assertFalse(EditorPredictionPolicy.allow_prediction(TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_URI));
  assertFalse(EditorPredictionPolicy.allow_prediction(TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_EMAIL_ADDRESS));
  assertFalse(EditorPredictionPolicy.allow_prediction(TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_NO_SUGGESTIONS));
  assertTrue(EditorPredictionPolicy.allow_prediction(TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_NORMAL));
}
```

Add handler tests proving excluded sessions neither retain a `DirectTap` nor issue a current-word request.

- [ ] **Step 2: Run focused policy/session tests and verify they fail**

Run: `./gradlew testDebugUnitTest --tests 'juloo.keyboard2.prediction.EditorPredictionPolicyTest' --tests 'juloo.keyboard2.prediction.PredictionSessionControllerTest' --tests 'juloo.keyboard2.KeyEventHandlerTest'`

Expected: compilation failure for `allow_prediction` or failed touch-retention assertions.

- [ ] **Step 3: Generalize the existing policy and apply it at collection time**

Rename/generalize `allow_next_word` to `allow_prediction` and use it both when creating the controller and when enabling touch collection. Do not rely on candidate-view visibility because its editor exceptions are broader. Clear evidence on `onStartInputView`, `onFinishInputView`, subtype change, and prediction-controller replacement.

- [ ] **Step 4: Verify failure latching leaves literal input unchanged**

Extend controller/session tests so a current-word decoder exception returns legacy candidates for that request, latches subsequent requests to legacy, closes native resources once, and never invokes replacement or another `commitText` call.

- [ ] **Step 5: Run focused and full tests**

Run: `./gradlew testDebugUnitTest --tests 'juloo.keyboard2.prediction.EditorPredictionPolicyTest' --tests 'juloo.keyboard2.prediction.PredictionSessionControllerTest' --tests 'juloo.keyboard2.KeyEventHandlerTest' && ./gradlew testDebugUnitTest`

Expected: all privacy, lifecycle, and JVM tests pass.

- [ ] **Step 6: Commit privacy enforcement**

```bash
git add srcs/juloo.keyboard2/prediction/EditorPredictionPolicy.java srcs/juloo.keyboard2/Keyboard2.java srcs/juloo.keyboard2/KeyEventHandler.java test/juloo.keyboard2/prediction/EditorPredictionPolicyTest.java test/juloo.keyboard2/prediction/PredictionSessionControllerTest.java test/KeyEventHandlerTest.java
git commit -m "fix: enforce touch prediction privacy boundaries"
```

### Task 8: Prove Native Touch Ranking And Resource Stability

**Files:**
- Modify: `androidTest/juloo.keyboard2/prediction/LatinimeDictionaryInstrumentationTest.java`
- Modify: `src/androidTest/java/juloo/keyboard2/CurrentlyTypedWordInstrumentationTest.java`

- [ ] **Step 1: Fix fixture selection and add a failing neighboring-key test**

Change `copy_fixture(String name)` to open `"latinime/" + name`. Add a compact QWERTY geometry fixture and compare text-only `gello` with a touch sequence whose first point is centered on `h`.

```java
@Test public void touch_near_h_promotes_hello_without_replacing_gello() throws Exception {
  LatinimeDictionary dictionary = LatinimeDictionary.open(copy_fixture(), "en");
  PredictionRequest request = current_word_request("gello", qwerty(), touchesNear("hello"));
  List<PredictionCandidate> candidates = dictionary.predict(request);
  assertTrue(index_of(candidates, "hello") >= 0);
  assertEquals("gello", request.composing_text());
  dictionary.close();
}
```

- [ ] **Step 2: Build and run the class-filtered instrumentation test**

Run: `./gradlew assembleDebug assembleDebugAndroidTest && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=juloo.keyboard2.prediction.LatinimeDictionaryInstrumentationTest`

Expected before implementation completion: the new ranking assertion fails; device/emulator connectivity failures are environment blockers and must be reported rather than treated as test failures.

- [ ] **Step 3: Tune only geometry/options needed to pass the fixture**

Correct coordinate normalization, grid neighbors, full-edit-distance option, and decoder result conversion. Do not add heuristic post-ranking specific to `hello` or the fixture.

- [ ] **Step 4: Add text-only, locale, close, and geometry-change loops**

Assert text-only requests work with `-1` coordinates, `en/de/de-CH/gsw` sessions use their pack locale, two geometry generations replace and close old proximity handles, 100 repeated predict/reset cycles stay stable, and dictionary/proximity close remains idempotent.

- [ ] **Step 5: Re-run instrumentation and native verification**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=juloo.keyboard2.prediction.LatinimeDictionaryInstrumentationTest && tools/verify_latinime_native.sh`

Expected: instrumentation passes and native packaging verification succeeds.

- [ ] **Step 6: Commit native integration coverage**

```bash
git add androidTest/juloo.keyboard2/prediction/LatinimeDictionaryInstrumentationTest.java src/androidTest/java/juloo/keyboard2/CurrentlyTypedWordInstrumentationTest.java
git commit -m "test: verify touch-aware native ranking"
```

### Task 9: Add Deterministic Accuracy And Latency Gates

**Files:**
- Create: `androidTest/assets/latinime/touch_accuracy_cases.tsv`
- Create: `androidTest/juloo.keyboard2/prediction/TouchAccuracyBenchmarkInstrumentationTest.java`
- Create: `tools/prediction/validate_touch_accuracy_cases.py`
- Create: `tools/prediction/test_validate_touch_accuracy_cases.py`
- Modify: `tools/prediction/README.md`

- [ ] **Step 1: Write failing corpus-validator tests**

Define TSV columns `locale`, `context`, `literal`, `target`, `tap_key_sequence`, and `valid_exact_word`. Require all four locales, unique cases, adjacent-key differences for typo rows, and valid UTF-8.

```python
def test_requires_all_release_locales(self):
    rows = [case("en", "", "gello", "hello", "hello", False)]
    with self.assertRaisesRegex(ValueError, "missing locales"):
        validate(rows)

def test_rejects_non_adjacent_typo(self):
    rows = complete_rows(replace_case("en", "hello", "zello", "hello"))
    with self.assertRaisesRegex(ValueError, "adjacent"):
        validate(rows)
```

- [ ] **Step 2: Run validator tests and verify the module is missing**

Run: `python3 -m unittest tools.prediction.test_validate_touch_accuracy_cases`

Expected: import failure for `validate_touch_accuracy_cases`.

- [ ] **Step 3: Implement validation and reviewed seed corpora**

Add at least 25 typo rows and 25 exact-word rows per locale. Include capitalization and context cases. For `gsw`, include multiple valid regional forms such as `nöd`, `nid`, and `ned` as exact-word rows; never designate one as correction of another. Validate adjacency against the checked-in Latin layout map in the Python script.

- [ ] **Step 4: Implement device benchmark calculations**

For every case, run text-only and touch-aware requests against the production pack, compute top-one/top-three recall, mean reciprocal rank, exact-word retention, and per-request elapsed nanoseconds after warm-up. Sort timing samples and report p50/p95/p99. Assert:

```java
assertTrue(touchTop3 >= textTop3 * 1.10);
assertTrue(eachLocaleTouchTop3 >= eachLocaleTextTop3);
assertTrue(overallExactRetentionDrop <= 0.005);
assertTrue(eachLocaleExactRetentionDrop <= 0.01);
assertTrue(p95Millis < 15.0);
assertTrue(p99Millis < 30.0);
```

Run a repeated-session loop and compare retained Java/native memory after forced idle checkpoints; fail on monotonic unbounded growth rather than a single noisy allocation delta.

- [ ] **Step 5: Run corpus validation and benchmark instrumentation**

Run: `python3 -m unittest tools.prediction.test_validate_touch_accuracy_cases && python3 tools/prediction/validate_touch_accuracy_cases.py androidTest/assets/latinime/touch_accuracy_cases.tsv && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=juloo.keyboard2.prediction.TouchAccuracyBenchmarkInstrumentationTest`

Expected: corpus validation passes and the benchmark reports passing overall and per-locale gates on the designated oldest supported test device. If a gate fails, keep experimental behavior unchanged and attach the measured report; do not weaken thresholds.

- [ ] **Step 6: Document the exact benchmark command and output fields**

Document device model/API, warm-up count, measured request count, metrics, and the requirement to preserve raw aggregate counts without logging typed text or coordinates.

- [ ] **Step 7: Commit benchmark gates**

```bash
git add androidTest/assets/latinime/touch_accuracy_cases.tsv androidTest/juloo.keyboard2/prediction/TouchAccuracyBenchmarkInstrumentationTest.java tools/prediction
git commit -m "test: gate touch-aware typing accuracy"
```

### Task 10: Run Complete Regression And Release Verification

**Files:**
- Modify only files required by failures directly attributable to this feature.

- [ ] **Step 1: Run all host-side tests**

Run: `./gradlew test && python3 -m unittest discover -s tools/prediction -p 'test_*.py'`

Expected: Gradle and Python suites pass with zero failures.

- [ ] **Step 2: Verify fixtures, native packaging, and APK builds**

Run: `./gradlew verifyLanguagePackFixture assembleDebug assembleRelease assembleDebugAndroidTest && tools/verify_latinime_native.sh`

Expected: fixture verification, debug/release builds, test APK build, and native packaging verification pass.

- [ ] **Step 3: Run full connected instrumentation**

Run: `./gradlew connectedDebugAndroidTest`

Expected: all connected tests pass. Record an unavailable device/emulator as an explicit verification gap.

- [ ] **Step 4: Inspect privacy and logging manually**

Run: `git diff $(git merge-base HEAD port-fork-features-to-upstream)..HEAD -- srcs vendor/latinime androidTest tools/prediction | rg -n 'Log|Logs|SharedPreferences|FileOutputStream|touch|coordinate'`

Expected: no typed text, candidate text, or coordinates are logged or persisted; only aggregate benchmark metrics and existing lifecycle logs remain.

- [ ] **Step 5: Inspect final scope and worktree**

Run: `git status --short && git diff --check $(git merge-base HEAD port-fork-features-to-upstream)..HEAD`

Expected: the worktree is clean and the complete feature diff has no whitespace errors. Confirm there are no raw hit-test changes, autocorrection, personal calibration, glide typing, new settings, or suggestion-strip redesign.

- [ ] **Step 6: Resolve verification failures at their owning task**

If verification exposes a feature regression, return to the task that owns the affected files, add a failing regression test, apply the minimal fix, rerun that task's focused and full checks, and commit only those explicitly listed files with `fix: resolve touch prediction regression`. If verification passes, create no additional commit.
