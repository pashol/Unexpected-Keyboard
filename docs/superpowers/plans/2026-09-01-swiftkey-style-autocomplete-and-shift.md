# SwiftKey-Style Autocomplete and Shift Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the center candidate a real completion that Space can accept, make Shift change suggestion casing without rewriting editor text, and give explicit candidate taps SwiftKey-style trailing-space behavior.

**Architecture:** Keep `cdict` as the current-word completion, emoji, known-word, and fallback provider. Change only the adapter ordering in `Suggestions`: ranked completions remain first and the literal typed word moves to the last available candidate slot. Model suggestion casing independently from editor text, derive it from the active Shift/Caps Lock state, and refresh candidates when that state changes. Keep automatic Space-bar completion undoable as a whole-word correction, while explicit candidate taps append an automatic space whose immediate Backspace removes only that space.

**Tech Stack:** Java, Android `InputConnection`/IME APIs, JUnit 4, Gradle Android unit tests

---

## Behavioral Contract

- `CandidatesView` already maps `suggestions[0]` to `candidates_middle`, `suggestions[1]` to `candidates_right`, and `suggestions[2]` to `candidates_left`; retain that mapping.
- For ambiguous current-word completions, keep the best ranked completion at index `0` and move or inject the literal typed word into the final occupied slot. With three entries, this yields `[best completion, second completion, typed word]`, visually `[center, right, left]`.
- If the main `cdict` lookup finds the typed word exactly, move that exact dictionary candidate to index `0` after personal candidates are merged. This takes precedence over personal prefix completions, so `zu` stays centered over personal `Zucker` and `des` stays centered over personal `deshalb`.
- Preserve the current single-unambiguous-completion behavior rather than injecting the typed prefix when only one completion exists.
- Space-bar autocomplete accepts index `0` only when it differs exactly from the current editor word. Immediate Backspace restores the pre-completion word.
- A completion tapped in the strip replaces the current word with the displayed candidate plus one space. Immediate Backspace removes only the space and leaves the chosen word.
- A next-word candidate tap continues to append rather than replace, includes one trailing space, and immediate Backspace removes only that space.
- One-shot Shift changes displayed candidates to initial-capital form and remains available to capitalize the next typed character.
- Caps Lock changes displayed candidates to uppercase and remains locked.
- Returning to the unshifted state restores dictionary-provided/default candidate casing by rerunning candidate generation; do not lowercase dictionary entries destructively.
- Tapping or accepting a candidate commits exactly the casing displayed in the strip.
- Shift never rewrites text already present in the editor and never moves the cursor.
- Password fields continue to hide candidates through `CandidatesView.should_show`; Shift still works as an ordinary modifier in those fields.
- Do not remove or replace `cdict` in this work.

### Task 0: Preserve Exact Dictionary Words Over Personal Prefixes

**Files:**
- Modify: `srcs/juloo.keyboard2/suggestions/Suggestions.java:151-215,286-333`
- Test: `test/juloo.keyboard2/suggestions/SuggestionsTest.java`

- [ ] **Step 1: Write a failing exact-word precedence test**

```java
@Test
public void centers_exact_dictionary_word_after_personal_prefixes()
{
  String[] candidates = { "Zucker", "zude", "zu" };
  boolean[] personal = { true, true, false };
  Suggestions.CandidateType[] types = {
    Suggestions.CandidateType.COMPLETION,
    Suggestions.CandidateType.COMPLETION,
    Suggestions.CandidateType.COMPLETION
  };

  Suggestions.place_exact_dictionary_word_first(candidates, personal, types, "zu");

  assertArrayEquals(new String[] { "zu", "Zucker", "zude" }, candidates);
  assertArrayEquals(new boolean[] { false, true, true }, personal);
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/pascal/Android/Sdk ./gradlew testDebugUnitTest --tests 'juloo.keyboard2.suggestions.SuggestionsTest'
```

Expected: compilation failure because `place_exact_dictionary_word_first` does not exist.

- [ ] **Step 3: Preserve the exact-result bit and promote only that word**

Record `r_exact.found` in `query_suggestions`. After `prepend_personal_candidates`, call `place_exact_dictionary_word_first` only when it is true, before calling `place_typed_word_last` for non-exact typed text. The helper must locate the matching candidate case-insensitively, rotate it to index `0`, and move matching personal/type metadata with it. Do not promote a typed word merely because it was injected as a fallback; that would make unknown prefixes unsafe for Space autocomplete.

- [ ] **Step 4: Run focused suggestions tests and verify GREEN**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/pascal/Android/Sdk ./gradlew testDebugUnitTest --tests 'juloo.keyboard2.suggestions.SuggestionsTest'
```

Expected: PASS.

- [ ] **Step 5: Remove diagnostics and commit**

Remove the temporary `Completion:` `android.util.Log` calls and its import. Commit only `Suggestions.java` and `SuggestionsTest.java`:

```bash
git add srcs/juloo.keyboard2/suggestions/Suggestions.java test/juloo.keyboard2/suggestions/SuggestionsTest.java
git commit -m "fix: keep exact dictionary words centered"
```

### Task 1: Put Ranked Completion in the Center Slot

**Files:**
- Modify: `srcs/juloo.keyboard2/suggestions/Suggestions.java:134-198,236-298`
- Test: `test/juloo.keyboard2/suggestions/SuggestionsTest.java:29-52,116-126`

- [ ] **Step 1: Replace the old promotion expectations with failing ranking tests**

Replace the tests that expect the typed word at index `0` and add metadata coverage:

```java
@Test
public void places_existing_typed_word_after_ranked_completions()
{
  String[] candidates = { "Erde", "Erdbeere", "Erden" };
  Suggestions.place_typed_word_last(candidates, "erde");
  assertArrayEquals(new String[] { "Erdbeere", "Erden", "Erde" }, candidates);
}

@Test
public void appends_missing_typed_word_after_ambiguous_completions()
{
  String[] candidates = { "Erdbeere", "Erden", null };
  Suggestions.place_typed_word_last(candidates, "erde");
  assertArrayEquals(new String[] { "Erdbeere", "Erden", "erde" }, candidates);
}

@Test
public void keeps_single_unambiguous_completion_without_literal_prefix()
{
  String[] candidates = { "Grosswangen", null, null };
  Suggestions.place_typed_word_last(candidates, "Grosswan");
  assertArrayEquals(new String[] { "Grosswangen", null, null }, candidates);
}

@Test
public void moves_typed_word_metadata_with_the_candidate()
{
  String[] candidates = { "Typed", "Completion", "Other" };
  boolean[] personal = { true, false, false };
  Suggestions.CandidateType[] types = {
    Suggestions.CandidateType.COMPLETION,
    Suggestions.CandidateType.COMPLETION,
    Suggestions.CandidateType.COMPLETION
  };

  Suggestions.place_typed_word_last(candidates, personal, types, "typed");

  assertArrayEquals(new String[] { "Completion", "Other", "Typed" }, candidates);
  assertArrayEquals(new boolean[] { false, false, true }, personal);
}
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
./gradlew testDebugUnitTest --tests 'juloo.keyboard2.suggestions.SuggestionsTest'
```

Expected: compilation or assertion failure because `place_typed_word_last` does not exist and current behavior promotes the literal word to index `0`.

- [ ] **Step 3: Implement last-slot placement while preserving rank and metadata**

Rename the `promote_typed_word` overloads to `place_typed_word_last`. Implement the full overload with this policy:

```java
static void place_typed_word_last(String[] candidates,
    boolean[] personal_candidates, CandidateType[] candidate_types,
    String typed_word)
{
  int count = count_suggestions(candidates);
  int matching_index = -1;
  for (int i = 0; i < count; i++)
    if (candidates[i].equalsIgnoreCase(typed_word))
      matching_index = i;

  if (matching_index >= 0)
  {
    String matching_word = candidates[matching_index];
    boolean matching_personal = personal_candidates != null
      && personal_candidates[matching_index];
    CandidateType matching_type = candidate_types == null ? null
      : candidate_types[matching_index];
    for (int i = matching_index; i < count - 1; i++)
    {
      candidates[i] = candidates[i + 1];
      if (personal_candidates != null)
        personal_candidates[i] = personal_candidates[i + 1];
      if (candidate_types != null)
        candidate_types[i] = candidate_types[i + 1];
    }
    candidates[count - 1] = matching_word;
    if (personal_candidates != null)
      personal_candidates[count - 1] = matching_personal;
    if (candidate_types != null)
      candidate_types[count - 1] = matching_type;
    return;
  }

  if (count < 2)
    return;
  int target = Math.min(count, candidates.length - 1);
  candidates[target] = typed_word;
  if (personal_candidates != null)
    personal_candidates[target] = false;
  if (candidate_types != null)
    candidate_types[target] = CandidateType.COMPLETION;
}
```

Update `query_suggestions` to call:

```java
place_typed_word_last(suggestions, personal_suggestions, types, capitalize
    ? Utils.capitalize_string(typed_word) : typed_word);
```

The two convenience overloads must delegate to the full overload exactly as before, using the new method name. Update every `SuggestionsTest` call from `promote_typed_word` to `place_typed_word_last`, including the emoji-isolation and personal-provenance tests.

- [ ] **Step 4: Run ranking tests and verify GREEN**

Run:

```bash
./gradlew testDebugUnitTest --tests 'juloo.keyboard2.suggestions.SuggestionsTest'
```

Expected: PASS.

- [ ] **Step 5: Commit the isolated ranking change**

```bash
git add srcs/juloo.keyboard2/suggestions/Suggestions.java test/juloo.keyboard2/suggestions/SuggestionsTest.java
git commit -m "fix: keep best completion in center candidate"
```

### Task 2: Make Space Autocomplete Require a Real Change

**Files:**
- Modify: `srcs/juloo.keyboard2/KeyEventHandler.java:659-677`
- Test: `test/KeyEventHandlerTest.java:79-127`

- [ ] **Step 1: Add a failing test for a literal center candidate**

```java
@Test
public void space_does_not_arm_autocomplete_undo_for_unchanged_candidate()
{
  FakeInputConnection connection = new FakeInputConnection("typed");
  KeyEventHandler handler = new_handler(connection);
  handler._typedword._enabled = true;
  handler._typedword.set_current_word("typed");
  handler._space_bar_auto_complete = true;
  handler._suggestions.suggestions[0] = "typed";
  handler._suggestions.count = 1;

  handler.handle_space_bar();

  assertEquals("typed ", connection.text());
  assertNull(handler.last_replaced_word);
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
./gradlew testDebugUnitTest --tests 'juloo.keyboard2.KeyEventHandlerTest.space_does_not_arm_autocomplete_undo_for_unchanged_candidate'
```

Expected: FAIL because `handle_space_bar` currently records an undoable replacement even when candidate text equals the typed word.

- [ ] **Step 3: Add the exact-change eligibility check**

Add this helper near `should_autocomplete_space`:

```java
static boolean changes_typed_word(String candidate, String typed_word)
{
  return candidate != null && !candidate.equals(typed_word);
}
```

Add it to the Space autocomplete condition:

```java
else if (_space_bar_auto_complete && _suggestions.count > 0
    && _suggestions.types[0] == Suggestions.CandidateType.COMPLETION
    && changes_typed_word(_suggestions.suggestions[0], _typedword.get())
    && !_typedword.is_selection_not_empty()
    && _typedword.cursor_relative() == 0)
```

Use exact equality deliberately: if Shift displays `Word` for typed `word`, Space must commit the displayed capitalization.

- [ ] **Step 4: Verify literal Space and full-word undo behaviors**

Run:

```bash
./gradlew testDebugUnitTest --tests 'juloo.keyboard2.KeyEventHandlerTest.space_does_not_arm_autocomplete_undo_for_unchanged_candidate' --tests 'juloo.keyboard2.KeyEventHandlerTest.backspace_after_space_bar_completion_restores_typed_word' --tests 'juloo.keyboard2.KeyEventHandlerTest.next_word_candidate_is_not_used_by_space_autocomplete'
```

Expected: PASS. The existing automatic-completion Backspace test must still restore the original word.

- [ ] **Step 5: Commit the Space eligibility change**

```bash
git add srcs/juloo.keyboard2/KeyEventHandler.java test/KeyEventHandlerTest.java
git commit -m "fix: autocomplete only changed center candidates"
```

### Task 3: Add Non-Destructive Suggestion Casing

**Files:**
- Modify: `srcs/juloo.keyboard2/suggestions/Suggestions.java:23-44,75-108,122-132,134-198`
- Test: `test/juloo.keyboard2/suggestions/SuggestionsTest.java`

- [ ] **Step 1: Add failing candidate-casing tests**

```java
@Test
public void title_case_changes_displayed_word_candidates_only()
{
  String[] candidates = { "hello", "WORLD", null };
  Suggestions.apply_candidate_case(candidates, Suggestions.CandidateCase.TITLE);
  assertArrayEquals(new String[] { "Hello", "World", null }, candidates);
}

@Test
public void upper_case_changes_displayed_word_candidates()
{
  String[] candidates = { "Hello", "world", null };
  Suggestions.apply_candidate_case(candidates, Suggestions.CandidateCase.UPPER);
  assertArrayEquals(new String[] { "HELLO", "WORLD", null }, candidates);
}

@Test
public void default_case_preserves_dictionary_casing()
{
  String[] candidates = { "iPhone", "McDonald", null };
  Suggestions.apply_candidate_case(candidates, Suggestions.CandidateCase.DEFAULT);
  assertArrayEquals(new String[] { "iPhone", "McDonald", null }, candidates);
}

@Test
public void changing_candidate_case_reports_only_real_mode_changes()
{
  Suggestions suggestions = new Suggestions(null, null);
  assertTrue(suggestions.set_candidate_case(Suggestions.CandidateCase.TITLE));
  assertFalse(suggestions.set_candidate_case(Suggestions.CandidateCase.TITLE));
  assertTrue(suggestions.set_candidate_case(Suggestions.CandidateCase.UPPER));
}
```

- [ ] **Step 2: Run casing tests and verify RED**

Run:

```bash
./gradlew testDebugUnitTest --tests 'juloo.keyboard2.suggestions.SuggestionsTest'
```

Expected: compilation failure because `CandidateCase`, `apply_candidate_case`, and `set_candidate_case` do not exist.

- [ ] **Step 3: Implement a casing mode owned by `Suggestions`**

Add:

```java
public static enum CandidateCase
{
  DEFAULT,
  TITLE,
  UPPER
}

private CandidateCase _candidate_case = CandidateCase.DEFAULT;

public boolean set_candidate_case(CandidateCase candidateCase)
{
  if (_candidate_case == candidateCase)
    return false;
  _candidate_case = candidateCase;
  return true;
}

static void apply_candidate_case(String[] candidates, CandidateCase candidateCase)
{
  if (candidateCase == CandidateCase.DEFAULT)
    return;
  for (int i = 0; i < candidates.length; i++)
  {
    String candidate = candidates[i];
    if (candidate == null)
      continue;
    if (candidateCase == CandidateCase.UPPER)
      candidates[i] = candidate.toUpperCase(Locale.ROOT);
    else
    {
      int first_end = candidate.offsetByCodePoints(0, 1);
      String first = candidate.substring(0, first_end).toUpperCase(Locale.ROOT);
      candidates[i] = first
        + candidate.substring(first_end).toLowerCase(Locale.ROOT);
    }
  }
}
```

After candidate ordering in `query_suggestions`, apply the active mode before counting:

```java
apply_candidate_case(suggestions, _candidate_case);
count = count_suggestions(suggestions);
```

Do the same at the end of `set_next_word_candidates`. Do not transform `emoji_suggestion`.

- [ ] **Step 4: Run suggestion tests and verify GREEN**

Run:

```bash
./gradlew testDebugUnitTest --tests 'juloo.keyboard2.suggestions.SuggestionsTest'
```

Expected: PASS.

- [ ] **Step 5: Commit the suggestion casing model**

```bash
git add srcs/juloo.keyboard2/suggestions/Suggestions.java test/juloo.keyboard2/suggestions/SuggestionsTest.java
git commit -m "feat: add non-destructive suggestion casing"
```

### Task 4: Drive Suggestion Casing from Shift Without Editing Text

**Files:**
- Modify: `srcs/juloo.keyboard2/Config.java:340-346`
- Modify: `srcs/juloo.keyboard2/Pointers.java:86-115`
- Modify: `srcs/juloo.keyboard2/Keyboard2View.java:132-142,194-199`
- Modify: `srcs/juloo.keyboard2/Keyboard2.java:609-617`
- Modify: `srcs/juloo.keyboard2/KeyEventHandler.java:37-39,74-77,147-155,696-732,765-779`
- Test: `test/KeyEventHandlerTest.java:14-33,158-190,397-418`

- [ ] **Step 1: Replace retroactive-capitalization tests with failing Shift-state tests**

Delete `cycle_word_case_rotates_lower_title_upper`, `cycle_word_case_rotates_supplementary_plane_letters`, `manual_shift_latch_leaves_word_unchanged_before_supplementary_letter`, and `successful_manual_shift_cycle_clears_physical_latch`.

Add:

```java
@Test
public void one_shot_shift_leaves_editor_text_unchanged()
{
  FakeInputConnection connection = new FakeInputConnection("hello");
  KeyEventHandler handler = new_handler(connection);
  handler._typedword._enabled = true;
  handler._typedword.set_current_word("hello");

  handler.mods_changed(Pointers.Modifiers.EMPTY.with_extra_mod(KeyValue.SHIFT),
      Pointers.ShiftState.SHIFTED);

  assertEquals("hello", connection.text());
  assertEquals(0, connection.delete_calls);
}

@Test
public void shift_at_an_internal_cursor_leaves_editor_text_unchanged()
{
  FakeInputConnection connection = new FakeInputConnection("sdatum");
  connection.cursor = 1;
  KeyEventHandler handler = new_handler(connection);
  handler._typedword._enabled = true;
  handler._typedword.set_current_word("sdatum");
  handler._typedword._w_cursor = -5;

  handler.mods_changed(Pointers.Modifiers.EMPTY.with_extra_mod(KeyValue.SHIFT),
      Pointers.ShiftState.SHIFTED);

  assertEquals("sdatum", connection.text());
  assertEquals(0, connection.delete_calls);
}

@Test
public void caps_lock_leaves_editor_text_unchanged()
{
  FakeInputConnection connection = new FakeInputConnection("hello");
  KeyEventHandler handler = new_handler(connection);
  handler._typedword._enabled = true;
  handler._typedword.set_current_word("hello");

  handler.mods_changed(Pointers.Modifiers.EMPTY.with_extra_mod(KeyValue.SHIFT),
      Pointers.ShiftState.LOCKED);

  assertEquals("hello", connection.text());
  assertEquals(0, connection.delete_calls);
}

@Test
public void shift_state_selects_candidate_casing_without_touching_editor_case()
{
  assertEquals(Suggestions.CandidateCase.DEFAULT,
      KeyEventHandler.candidate_case_for_shift(Pointers.ShiftState.OFF));
  assertEquals(Suggestions.CandidateCase.TITLE,
      KeyEventHandler.candidate_case_for_shift(Pointers.ShiftState.SHIFTED));
  assertEquals(Suggestions.CandidateCase.UPPER,
      KeyEventHandler.candidate_case_for_shift(Pointers.ShiftState.LOCKED));
}
```

- [ ] **Step 2: Run the focused handler tests and verify RED**

Run:

```bash
./gradlew testDebugUnitTest --tests 'juloo.keyboard2.KeyEventHandlerTest.one_shot_shift_leaves_editor_text_unchanged' --tests 'juloo.keyboard2.KeyEventHandlerTest.shift_at_an_internal_cursor_leaves_editor_text_unchanged' --tests 'juloo.keyboard2.KeyEventHandlerTest.caps_lock_leaves_editor_text_unchanged'
```

Expected: compilation failure because `Pointers.ShiftState` and the new `mods_changed` signature do not exist.

- [ ] **Step 3: Replace the callback’s retroactive-latch contract with lock state**

Add this state model to `Pointers`:

```java
public static enum ShiftState
{
  OFF,
  SHIFTED,
  LOCKED
}

public ShiftState shift_state()
{
  int flags = getKeyFlags(KeyValue.SHIFT);
  if (flags < 0)
    return ShiftState.OFF;
  return (flags & FLAG_P_LOCKED) != 0 ? ShiftState.LOCKED
    : ShiftState.SHIFTED;
}
```

Change `Config.IKeyEventHandler` to:

```java
public void mods_changed(Pointers.Modifiers mods, Pointers.ShiftState shift_state);
```

Change `Keyboard2View.updateFlags()` to pass the explicit state:

```java
_mods = _pointers.getModifiers();
_config.handler.mods_changed(_mods, _pointers.shift_state());
```

Delete these now-obsolete APIs:

- `Pointers.is_manually_latched`
- `Pointers.clear_manual_latch`
- `Keyboard2View.clear_shift_latch`
- `KeyEventHandler.IReceiver.clear_shift_latch`
- `Keyboard2.Receiver.clear_shift_latch`
- The test receiver’s `shift_latch_cleared` field and callback

- [ ] **Step 4: Replace editor mutation with candidate-mode refresh**

Delete `_manual_shift_latched`, `cycle_typed_word_case`, and `cycle_word_case` from `KeyEventHandler`.

Implement `mods_changed` as:

```java
@Override
public void mods_changed(Pointers.Modifiers mods, Pointers.ShiftState shift_state)
{
  update_meta_state(mods);
  Suggestions.CandidateCase candidate_case = candidate_case_for_shift(shift_state);
  if (_suggestions.set_candidate_case(candidate_case))
    _suggestions.currently_typed_word(_typedword.get(),
        _typedword.sentence_start(), _typedword.preceding_words_for_next_word());
}

static Suggestions.CandidateCase candidate_case_for_shift(
    Pointers.ShiftState shift_state)
{
  if (shift_state == Pointers.ShiftState.SHIFTED)
    return Suggestions.CandidateCase.TITLE;
  if (shift_state == Pointers.ShiftState.LOCKED)
    return Suggestions.CandidateCase.UPPER;
  return Suggestions.CandidateCase.DEFAULT;
}
```

This refreshes candidates from their source whenever Shift mode changes, avoiding destructive uppercase/lowercase round trips. It does not clear the physical latch, so the existing `Pointers` lifecycle applies one-shot Shift to the next key and clears it normally; locked Shift remains active.

- [ ] **Step 5: Verify Shift behavior and the broader handler suite**

Run:

```bash
./gradlew testDebugUnitTest --tests 'juloo.keyboard2.KeyEventHandlerTest' --tests 'juloo.keyboard2.suggestions.SuggestionsTest'
```

Expected: PASS. No test should expect Shift to call `deleteSurroundingText`, `commitText`, or clear a manual latch.

- [ ] **Step 6: Commit the Shift integration**

```bash
git add srcs/juloo.keyboard2/Config.java srcs/juloo.keyboard2/Pointers.java srcs/juloo.keyboard2/Keyboard2View.java srcs/juloo.keyboard2/Keyboard2.java srcs/juloo.keyboard2/KeyEventHandler.java test/KeyEventHandlerTest.java
git commit -m "feat: apply shift casing to suggestions only"
```

### Task 5: Add SwiftKey-Style Space After Explicit Candidate Taps

**Files:**
- Modify: `srcs/juloo.keyboard2/KeyEventHandler.java:157-200,679-694`
- Test: `test/KeyEventHandlerTest.java:86-156,420-478`

- [ ] **Step 1: Replace the no-space tests with failing explicit-space tests**

Replace `entering_suggestion_does_not_append_a_space` and `tapped_suggestion_does_not_arm_full_word_undo` with:

```java
@Test
public void tapped_completion_replaces_word_and_appends_space()
{
  FakeInputConnection connection = new FakeInputConnection("Informa");
  KeyEventHandler handler = new_handler(connection);
  handler._typedword._enabled = true;
  handler._typedword.set_current_word("Informa");

  handler.suggestion_entered("Informatik");

  assertEquals("Informatik ", connection.text());
  assertNull(handler.last_replaced_word);
}

@Test
public void backspace_after_tapped_completion_removes_only_added_space()
{
  FakeInputConnection connection = new FakeInputConnection("Informa");
  KeyEventHandler handler = new_handler(connection);
  handler._typedword._enabled = true;
  handler._typedword.set_current_word("Informa");

  handler.suggestion_entered("Informatik");
  handler.handle_backspace();

  assertEquals("Informatik", connection.text());
}

@Test
public void backspace_after_tapped_next_word_removes_only_added_space()
{
  FakeInputConnection connection = new FakeInputConnection("hello ");
  KeyEventHandler handler = new_handler(connection);

  handler.candidate_entered("world", Suggestions.CandidateType.NEXT_WORD);
  handler.handle_backspace();

  assertEquals("hello world", connection.text());
}
```

Update `candidate_entry_preserves_completion_replacement_behavior` to expect `"hello "`. Keep `backspace_after_space_bar_completion_restores_typed_word` unchanged as the regression test for automatic full-word undo.

- [ ] **Step 2: Extend the fake editor to model ordinary Backspace**

Add `android.view.KeyEvent` to the test imports. In `FakeInputConnection.invoke`, handle the key-up event once:

```java
if (method.getName().equals("sendKeyEvent"))
{
  KeyEvent event = (KeyEvent)args[0];
  if (event.getAction() == KeyEvent.ACTION_UP
      && event.getKeyCode() == KeyEvent.KEYCODE_DEL && cursor > 0)
  {
    text.deleteCharAt(cursor - 1);
    cursor--;
  }
}
```

- [ ] **Step 3: Run the candidate tests and verify RED**

Run:

```bash
./gradlew testDebugUnitTest --tests 'juloo.keyboard2.KeyEventHandlerTest.tapped_completion_replaces_word_and_appends_space' --tests 'juloo.keyboard2.KeyEventHandlerTest.backspace_after_tapped_completion_removes_only_added_space' --tests 'juloo.keyboard2.KeyEventHandlerTest.backspace_after_tapped_next_word_removes_only_added_space'
```

Expected: FAIL because tapped completions currently omit the space and next-word insertion does not mark its space as automatic.

- [ ] **Step 4: Append and track the explicit candidate space without arming correction undo**

Change explicit completion entry to:

```java
@Override
public void suggestion_entered(String text)
{
  replace_suggestion(text + " ", false);
  last_replaced_word = null;
  _last_action = LastAction.OTHER;
  _next_last_action = LastAction.OTHER;
  _auto_space_inserted = true;
}
```

At the end of `next_word_entered`, mark its trailing space consistently:

```java
_last_action = LastAction.OTHER;
_next_last_action = LastAction.OTHER;
_auto_space_inserted = true;
```

Do not set `last_replaced_word` for either explicit tap. Therefore `handle_backspace` follows its ordinary key-delete branch and removes just the trailing space. Keep `handle_space_bar` calling `replace_suggestion(..., true)` so automatic correction still records the original word and immediate Backspace restores it.

- [ ] **Step 5: Verify both explicit and automatic Backspace contracts**

Run:

```bash
./gradlew testDebugUnitTest --tests 'juloo.keyboard2.KeyEventHandlerTest'
```

Expected: PASS, including:

- Explicit completion tap then Backspace leaves the selected word.
- Explicit next-word tap then Backspace leaves the selected word.
- Space-bar autocomplete then Backspace restores the originally typed word.
- Completion taps commit the exact displayed string supplied by `CandidatesView`.

- [ ] **Step 6: Commit explicit candidate spacing**

```bash
git add srcs/juloo.keyboard2/KeyEventHandler.java test/KeyEventHandlerTest.java
git commit -m "feat: append space after tapped candidates"
```

### Task 6: Full Regression and Device-Level Verification

**Files:**
- Verify only

- [ ] **Step 1: Run all JVM unit tests**

Run:

```bash
./gradlew testDebugUnitTest
```

Expected: PASS with no test failures.

- [ ] **Step 2: Build debug and release variants**

Run:

```bash
./gradlew assembleDebug assembleRelease
```

Expected: both APK variants build successfully.

- [ ] **Step 3: Check the patch for whitespace errors**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 4: Verify behavior manually in a normal text editor**

Install the debug APK and verify these exact scenarios:

```text
1. Type an ambiguous prefix: the best completion is centered and the literal input is not centered.
2. Press Space: the center completion plus a space is committed.
3. Immediately press Backspace: the original typed prefix is restored.
4. Tap a completion: the displayed completion plus a space is committed.
5. Immediately press Backspace: only the space is removed.
6. Press Shift after typing a lowercase word: editor text does not change; suggestions become Initial Capital.
7. Type the next letter: it is uppercase and the earlier word text remains untouched.
8. Enable Caps Lock: suggestions become ALL CAPS and committed candidates preserve ALL CAPS.
9. Disable Shift/Caps Lock: dictionary/default suggestion casing returns.
10. Type `sDatum` and `dFreiziit` by activating Shift at the internal capital position.
```

- [ ] **Step 5: Verify behavior manually in a password field**

Confirm that the candidate strip remains hidden and mixed-case strings can be entered with ordinary Shift and Caps Lock. No password text may be queried for suggestions or logged.

- [ ] **Step 6: Confirm `cdict` remains intact**

Inspect the final diff and ensure it does not remove:

- `Config.current_dictionary`
- `Config.emoji_dictionary`
- `Suggestions.query_suggestions`
- `Suggestions.query_emoji`
- `KeyEventHandler.dictionary_knows_word`
- `vendor/cdict`

- [ ] **Step 7: Commit any verification-only test adjustment if needed**

Only if a legitimate platform-neutral test adjustment was required:

```bash
git add test/KeyEventHandlerTest.java test/juloo.keyboard2/suggestions/SuggestionsTest.java
git commit -m "test: cover swiftkey-style completion flow"
```

Do not create an empty commit.
