# Port Fork Features To Latest Upstream Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the fork-only keyboard behavior and Swiss German QWERTZ layout on the latest upstream code while retaining the fork application ID.

**Architecture:** Build on upstream `master`, preserving its four-slot candidate model (three words plus emoji), split-layout handling, and stateful suggestion-entry keys. Add fork behavior as focused extensions to `EditorConfig`, `CurrentlyTypedWord`, `Suggestions`, `KeyEventHandler`, and a new `UserDictionary`; register the layout through the existing generators.

**Tech Stack:** Java 8, Android `InputMethodService`, Android preference XML and Storage Access Framework, JUnit 4, Gradle Kotlin DSL, Python layout generators.

---

## File Structure

- `build.gradle.kts`: retain upstream build configuration and set only the fork application ID.
- `srcs/juloo.keyboard2/EditorConfig.java`: derive punctuation auto-space eligibility from the active `EditorInfo`.
- `srcs/juloo.keyboard2/Config.java`: read the three new preferences.
- `srcs/juloo.keyboard2/CurrentlyTypedWord.java`: expose sentence-start state alongside current-word updates.
- `srcs/juloo.keyboard2/suggestions/Suggestions.java`: merge case-insensitive, sentence-aware, deduplicated, user-dictionary, and typed-word results into upstream candidates.
- `srcs/juloo.keyboard2/suggestions/CandidatesView.java`: support personal-suggestion removal without altering upstream emoji and split-layout rendering.
- `srcs/juloo.keyboard2/UserDictionary.java`: private UTF-8 word storage plus import/export operations.
- `srcs/juloo.keyboard2/KeyEventHandler.java`: case cycling, auto-spacing, learning, and preference refresh integration.
- `srcs/juloo.keyboard2/Keyboard2.java`: initialize the personal dictionary and preserve it across keyboard lifecycle events.
- `srcs/juloo.keyboard2/SettingsActivity.java`, `res/xml/settings.xml`, `res/values/strings.xml`: expose preferences and SAF actions.
- `srcs/layouts/latn_qwertz_de_ch.xml`, `gen_method_xml.py`, generated `res/values/layouts.xml`, and generated `res/xml/method.xml`: register the Swiss German layout.
- `test/juloo.keyboard2/CurrentlyTypedWordTest.java`, `SuggestionsTest.java`, `UserDictionaryTest.java`, and `KeyEventHandlerTest.java`: focused unit coverage for new non-UI logic.

### Task 1: Rebase the Work Branch on Latest Upstream

**Files:**
- Create: worktree at `/home/pascal/Code/Unexpected-Keyboard-upstream-port`
- Copy: `docs/superpowers/specs/2026-07-18-port-fork-features-to-upstream-design.md`
- Copy: `docs/superpowers/plans/2026-07-18-port-fork-features-to-upstream.md`
- Modify: `build.gradle.kts:15-25`

- [ ] **Step 1: Preserve the current design-only branch under a distinct name**

Run:

```bash
git branch -m port-fork-features-to-upstream port-fork-features-to-upstream-design
```

Expected: the current branch is renamed; no source changes are lost.

- [ ] **Step 2: Create the requested branch and a dedicated worktree from upstream**

Run:

```bash
git fetch upstream
git branch port-fork-features-to-upstream upstream/master
git worktree add /home/pascal/Code/Unexpected-Keyboard-upstream-port port-fork-features-to-upstream
```

Expected: `git -C /home/pascal/Code/Unexpected-Keyboard-upstream-port merge-base --is-ancestor upstream/master HEAD` exits `0`.

- [ ] **Step 3: Copy the approved design and this plan into the upstream worktree**

Run:

```bash
mkdir -p /home/pascal/Code/Unexpected-Keyboard-upstream-port/docs/superpowers/specs /home/pascal/Code/Unexpected-Keyboard-upstream-port/docs/superpowers/plans
git show port-fork-features-to-upstream-design:docs/superpowers/specs/2026-07-18-port-fork-features-to-upstream-design.md > /home/pascal/Code/Unexpected-Keyboard-upstream-port/docs/superpowers/specs/2026-07-18-port-fork-features-to-upstream-design.md
cp /home/pascal/Code/Unexpected-Keyboard/docs/superpowers/plans/2026-07-18-port-fork-features-to-upstream.md /home/pascal/Code/Unexpected-Keyboard-upstream-port/docs/superpowers/plans/2026-07-18-port-fork-features-to-upstream.md
```

Expected: both files exist in the new worktree and are identical to the approved documents.

- [ ] **Step 4: Preserve the fork installation identity**

Change `build.gradle.kts` in the new worktree:

```kotlin
android {
  namespace = "juloo.keyboard2"
  compileSdkVersion = "android-36"

  defaultConfig {
    applicationId = "juloo.keyboard2.pashol"
```

Do not change `namespace`, `versionCode`, `versionName`, target SDK, or upstream dependencies.

- [ ] **Step 5: Commit the upstream baseline and project documents**

Run:

```bash
git -C /home/pascal/Code/Unexpected-Keyboard-upstream-port add build.gradle.kts docs/superpowers/specs/2026-07-18-port-fork-features-to-upstream-design.md docs/superpowers/plans/2026-07-18-port-fork-features-to-upstream.md
git -C /home/pascal/Code/Unexpected-Keyboard-upstream-port commit -m "build: preserve fork application identity"
```

Expected: the new branch starts from upstream and contains only the application-ID divergence and approved documents.

### Task 2: Add the Swiss German Layout Through Generators

**Files:**
- Create: `srcs/layouts/latn_qwertz_de_ch.xml`
- Modify: `gen_method_xml.py:31`
- Modify: generated `res/values/layouts.xml`
- Modify: generated `res/xml/method.xml`

- [ ] **Step 1: Verify the Swiss layout is absent from current generated resources**

Run:

```bash
python -c "from pathlib import Path; assert 'latn_qwertz_de_ch' not in Path('res/values/layouts.xml').read_text()"
```

Expected: exits `0` before the layout is added.

- [ ] **Step 2: Add the fork layout XML and make it the `de_CH` default**

Create `srcs/layouts/latn_qwertz_de_ch.xml` from fork commit `329bee6`. Keep the keyboard name `QWERTZ (Schweiz)`, include the QWERTZ letter rows and Swiss `ä`, `ö`, and `ü` swipes, and retain only allowed editing actions: `delete`, `delete_word`, and `switch_clipboard` on backspace.

Replace the upstream locale declaration:

```python
loc("de_CH", "latin", "latn_qwertz_de", extra_keys="accent_trema:ä:ö:ü@u|ß"),
```

with:

```python
loc("de_CH", "latin", "latn_qwertz_de_ch", extra_keys="accent_trema:ä:ö:ü@u|ß"),
```

- [ ] **Step 3: Generate and validate all layout artifacts**

Run:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew genLayoutsList genMethodXml checkKeyboardLayouts
python -c "from pathlib import Path; assert 'latn_qwertz_de_ch' in Path('res/values/layouts.xml').read_text(); assert 'default_layout=latn_qwertz_de_ch' in Path('res/xml/method.xml').read_text()"
```

Expected: all tasks succeed; `res/values/layouts.xml` and `res/xml/method.xml` include `latn_qwertz_de_ch`; the layout checker accepts the XML.

- [ ] **Step 4: Commit the layout**

Run:

```bash
git add srcs/layouts/latn_qwertz_de_ch.xml gen_method_xml.py res/values/layouts.xml res/xml/method.xml check_layout.output
git commit -m "feat: add Swiss German QWERTZ layout"
```

### Task 3: Add Editor and Typed-Word Context

**Files:**
- Modify: `srcs/juloo.keyboard2/EditorConfig.java:33-104`
- Modify: `srcs/juloo.keyboard2/CurrentlyTypedWord.java:19-258`
- Modify: `srcs/juloo.keyboard2/KeyEventHandler.java:151-155`
- Create: `test/juloo.keyboard2/CurrentlyTypedWordTest.java`

- [ ] **Step 1: Write sentence-boundary tests**

Create `CurrentlyTypedWordTest.java` with these assertions:

```java
@Test public void detects_sentence_starts()
{
  assertTrue(CurrentlyTypedWord.sentence_start_from_context("", 0));
  assertTrue(CurrentlyTypedWord.sentence_start_from_context("Text. he", 2));
  assertTrue(CurrentlyTypedWord.sentence_start_from_context("Text! he", 2));
  assertTrue(CurrentlyTypedWord.sentence_start_from_context("Text? he", 2));
  assertTrue(CurrentlyTypedWord.sentence_start_from_context("Text\nhe", 2));
  assertFalse(CurrentlyTypedWord.sentence_start_from_context("Text, he", 2));
  assertFalse(CurrentlyTypedWord.sentence_start_from_context("Text he", 2));
}
```

- [ ] **Step 2: Run the sentence-boundary test to verify it fails**

Run:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew test --tests juloo.keyboard2.CurrentlyTypedWordTest
```

Expected: FAIL because `sentence_start_from_context` does not exist.

- [ ] **Step 3: Extend editor configuration and current-word callbacks**

In `EditorConfig`, add `boolean no_auto_space_after_punct` and set it to `true` for non-text editors and text variations `PASSWORD`, `VISIBLE_PASSWORD`, `WEB_PASSWORD`, `URI`, `EMAIL_ADDRESS`, and `WEB_EMAIL_ADDRESS`.

In `CurrentlyTypedWord`, add `boolean _at_sentence_start`, a package-visible pure helper:

```java
static boolean sentence_start_from_context(String text, int word_length)
{
  int i = text.length() - word_length - 1;
  while (i >= 0 && Character.isWhitespace(text.charAt(i))) i--;
  return i < 0 || ".!?\n".indexOf(text.charAt(i)) >= 0;
}
```

Compute the state after each fetched surrounding-text refresh. Change `Callback` to:

```java
public void currently_typed_word(String word, boolean sentence_start);
```

Update `KeyEventHandler.currently_typed_word` to forward both arguments to `Suggestions`.

- [ ] **Step 4: Run typed-word tests**

Run:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew test --tests juloo.keyboard2.CurrentlyTypedWordTest
```

Expected: PASS.

- [ ] **Step 5: Commit the context plumbing**

Run:

```bash
git add srcs/juloo.keyboard2/EditorConfig.java srcs/juloo.keyboard2/CurrentlyTypedWord.java srcs/juloo.keyboard2/KeyEventHandler.java test/juloo.keyboard2/CurrentlyTypedWordTest.java
git commit -m "feat: track sentence starts for suggestions"
```

### Task 4: Port Suggestion Lookup, Ordering, and Presentation

**Files:**
- Modify: `srcs/juloo.keyboard2/Config.java:47-205`
- Modify: `srcs/juloo.keyboard2/suggestions/Suggestions.java:34-124`
- Modify: `srcs/juloo.keyboard2/suggestions/CandidatesView.java:52-117`
- Modify: `res/values/styles.xml:4-31`
- Modify: `res/xml/settings.xml:16-19`
- Modify: `res/values/strings.xml`
- Create: `test/juloo.keyboard2/SuggestionsTest.java`

- [ ] **Step 1: Write failing pure suggestion tests**

Extract package-visible helpers in `Suggestions` and test them:

```java
@Test public void deduplicates_case_insensitively()
{
  assertTrue(Suggestions.already_in(new String[] { "Erde", null, null }, 1, "erde"));
  assertFalse(Suggestions.already_in(new String[] { "Erde", null, null }, 1, "Erdbeere"));
}

@Test public void promotes_typed_word_when_candidates_are_ambiguous()
{
  String[] candidates = { "Erdbeere", "Erden", null };
  Suggestions.promote_typed_word(candidates, "erde");
  assertEquals("erde", candidates[0]);
}

@Test public void keeps_single_unambiguous_completion()
{
  String[] candidates = { "Grosswangen", null, null };
  Suggestions.promote_typed_word(candidates, "Grosswan");
  assertEquals("Grosswangen", candidates[0]);
}
```

- [ ] **Step 2: Run suggestion tests to verify they fail**

Run:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew test --tests juloo.keyboard2.SuggestionsTest
```

Expected: FAIL because the helper methods and sentence argument do not exist.

- [ ] **Step 3: Add preferences and merge fork behavior into upstream candidates**

Add `capitalize_suggestions_at_sentence_start` (default `true`) to `Config` and Settings. Change `Suggestions.currently_typed_word` to receive `sentence_start` and preserve upstream `_enabled`, emoji lookup, and `MAX_COUNT` behavior.

Use the substitution-normalized typed word for the existing exact, suffix, and distance queries. Query the opposite first-character case if the exact result has no useful prefix, keep dictionary spelling for alternate-case exact matches, skip case-insensitive duplicates, and capitalize results when the typed word is capitalized or sentence-start capitalization is enabled.

Implement exactly this promotion rule in `promote_typed_word`: move a matching typed word to index `0`; inject it at index `0` when there are two or more word candidates; do not inject it when the only candidate is an unambiguous completion. Do not alter `emoji_suggestion` or candidate index `3`.

Increase only the existing candidate text sizing/padding values needed to match the fork's readable one-line candidate presentation. Retain upstream `candidates_emoji`, `candidates_gap`, and split-layout placement.

- [ ] **Step 4: Run suggestion tests and the whole unit suite**

Run:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew test --tests juloo.keyboard2.SuggestionsTest
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew test
```

Expected: both commands pass.

- [ ] **Step 5: Commit the suggestion behavior**

Run:

```bash
git add srcs/juloo.keyboard2/Config.java srcs/juloo.keyboard2/suggestions/Suggestions.java srcs/juloo.keyboard2/suggestions/CandidatesView.java res/values/styles.xml res/xml/settings.xml res/values/strings.xml test/juloo.keyboard2/SuggestionsTest.java
git commit -m "feat: improve suggestion matching and ordering"
```

### Task 5: Add the Personal Dictionary and SAF Settings

**Files:**
- Create: `srcs/juloo.keyboard2/UserDictionary.java`
- Modify: `srcs/juloo.keyboard2/Keyboard2.java:126-146`
- Modify: `srcs/juloo.keyboard2/suggestions/Suggestions.java`
- Modify: `srcs/juloo.keyboard2/suggestions/CandidatesView.java:158-173`
- Modify: `srcs/juloo.keyboard2/SettingsActivity.java:1-49`
- Modify: `srcs/juloo.keyboard2/Config.java`
- Modify: `res/xml/settings.xml`
- Modify: `res/values/strings.xml`
- Create: `test/juloo.keyboard2/UserDictionaryTest.java`

- [ ] **Step 1: Write failing dictionary storage tests**

Write tests against a temporary directory constructor:

```java
@Test public void exact_match_precedes_newer_prefix_match() throws Exception
{
  UserDictionary dict = new UserDictionary(tempDir);
  dict.add("Erdbeere");
  dict.add("Erde");
  assertEquals(Arrays.asList("Erde", "Erdbeere"), dict.find_prefix("erde", 2));
}

@Test public void replace_import_clears_existing_words() throws Exception
{
  UserDictionary dict = new UserDictionary(tempDir);
  dict.add("oldword");
  assertEquals(1, dict.import_lines(Arrays.asList("newword"), true));
  assertFalse(dict.contains("oldword"));
  assertTrue(dict.contains("newword"));
}
```

- [ ] **Step 2: Run storage tests to verify they fail**

Run:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew test --tests juloo.keyboard2.UserDictionaryTest
```

Expected: FAIL because `UserDictionary` does not exist.

- [ ] **Step 3: Implement dictionary storage and suggestion integration**

Implement `UserDictionary` with a singleton `init(Context)`, a package-visible `UserDictionary(File)` constructor for tests, private `user_words.txt` storage, case-insensitive `contains`, exact-first `find_prefix`, minimum three-character `add`, and `remove` that persist after every mutation.

Implement `exportTo(ContentResolver, Uri)` and `importFrom(ContentResolver, Uri, boolean)` with `StandardCharsets.UTF_8`; ignore blank and shorter-than-three-character lines; return `-1` on I/O failure. Factor parsing into `import_lines(List<String>, boolean)` for unit tests.

Initialize it once in `Keyboard2.onCreate()` after global config initialization. Add `user_dictionary_enabled` to `Config` and Settings with default `false`. In `Suggestions`, prepend up to two personal candidates, deduplicate against dictionary candidates, then apply the Task 4 promotion rule.

In `CandidatesView.setup_item_view`, attach a 600 ms `postDelayed` touch handler. It removes only an enabled personal-dictionary candidate, consumes the release after removal, and leaves ordinary and emoji candidate clicks unchanged.

- [ ] **Step 4: Add SAF actions**

In `SettingsActivity`, add `user_dictionary_export` and `user_dictionary_import` click handlers. Export uses `Intent.ACTION_CREATE_DOCUMENT`, MIME type `text/plain`, and a default `user_dictionary.txt` name. Import uses `Intent.ACTION_OPEN_DOCUMENT`, then an `AlertDialog` with merge and replace options. Show a success, no-new-words, or error toast based on `importFrom`.

- [ ] **Step 5: Run dictionary tests and build the settings resource**

Run:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew test --tests juloo.keyboard2.UserDictionaryTest
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
```

Expected: tests pass and the debug build resolves all new resource IDs.

- [ ] **Step 6: Commit the personal dictionary**

Run:

```bash
git add srcs/juloo.keyboard2/UserDictionary.java srcs/juloo.keyboard2/Keyboard2.java srcs/juloo.keyboard2/suggestions/Suggestions.java srcs/juloo.keyboard2/suggestions/CandidatesView.java srcs/juloo.keyboard2/SettingsActivity.java srcs/juloo.keyboard2/Config.java res/xml/settings.xml res/values/strings.xml test/juloo.keyboard2/UserDictionaryTest.java
git commit -m "feat: add opt-in personal dictionary"
```

### Task 6: Port Shift Case Cycling, Punctuation Auto-Space, and Learning

**Files:**
- Modify: `srcs/juloo.keyboard2/KeyEventHandler.java:39-263`
- Modify: `srcs/juloo.keyboard2/Config.java`
- Modify: `res/xml/settings.xml`
- Modify: `res/values/strings.xml`
- Create: `test/juloo.keyboard2/KeyEventHandlerTest.java`

- [ ] **Step 1: Write failing behavior-helper tests**

Expose package-visible pure helpers and test them:

```java
@Test public void cycles_word_case()
{
  assertEquals("Word", KeyEventHandler.cycle_word_case("word"));
  assertEquals("WORD", KeyEventHandler.cycle_word_case("Word"));
  assertEquals("word", KeyEventHandler.cycle_word_case("WORD"));
}

@Test public void identifies_auto_space_punctuation()
{
  assertTrue(KeyEventHandler.is_auto_space_punct('.'));
  assertTrue(KeyEventHandler.is_auto_space_punct(':'));
  assertFalse(KeyEventHandler.is_auto_space_punct('-'));
}
```

- [ ] **Step 2: Run key-handler tests to verify they fail**

Run:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew test --tests juloo.keyboard2.KeyEventHandlerTest
```

Expected: FAIL because the helpers do not exist.

- [ ] **Step 3: Implement manual Shift case cycling**

Track manual Shift key-down independently from automatic capitalization. When `mods_changed` latches manual Shift, call `handle_shift_retroactive_cap`. It must return without mutation if the current word is empty, there is a selection, the cursor is not at the word end, or the next character is a letter. Otherwise replace the surrounding word with `cycle_word_case(word)`, update `CurrentlyTypedWord`, refresh suggestions, and clear the Shift state.

Use upstream `replace_surrounding_text(remove_before, remove_after, new_text)` and `_typedword.cursor_relative()` rather than the fork's obsolete before-cursor-only replacement helper.

- [ ] **Step 4: Implement auto-spacing and word learning without bypassing upstream last-action logic**

Add `auto_space_after_punct` to `Config` and the Behavior settings category with default `true`. Derive an active `_auto_space_after_punct` from that preference and `EditorConfig.no_auto_space_after_punct` in `started` and the upstream preference-refresh path.

For a single enabled punctuation character `.`, `!`, `?`, `,`, `;`, or `:`, remove a preceding automatic/suggestion space, commit punctuation, and append one space only when the next editor character is not already a space. Keep `_autocap`, `_typedword`, and upstream `LastAction` synchronized for each mutation.

Before a non-letter delimiter clears a word of at least three characters, add it to the enabled personal dictionary only when it is not in the active `Cdict`. After a space-bar autocomplete is undone with backspace, mark that action and make the following delimiter learn the original typed word instead of applying autocomplete again. Preserve upstream's current `LastAction.SUGGESTION_ENTERED` handling and its fix that suggestion entry itself does not add a space.

- [ ] **Step 5: Run targeted and complete tests**

Run:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew test --tests juloo.keyboard2.KeyEventHandlerTest
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew test
```

Expected: both commands pass.

- [ ] **Step 6: Commit input behavior**

Run:

```bash
git add srcs/juloo.keyboard2/KeyEventHandler.java srcs/juloo.keyboard2/Config.java res/xml/settings.xml res/values/strings.xml test/juloo.keyboard2/KeyEventHandlerTest.java
git commit -m "feat: add case cycling and punctuation spacing"
```

### Task 7: Verify Generated Artifacts and Upstream Compatibility

**Files:**
- Modify: generated `res/values/layouts.xml`, `res/xml/method.xml`, and `check_layout.output` only if generation changes them

- [ ] **Step 1: Initialize required submodules**

Run:

```bash
git submodule update --init
```

Expected: `vendor/cdict/java` exists.

- [ ] **Step 2: Regenerate and validate source-derived artifacts**

Run:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew genLayoutsList genMethodXml checkKeyboardLayouts compileComposeSequences
git diff --check
```

Expected: Gradle succeeds and `git diff --check` has no output.

- [ ] **Step 3: Run the complete test suite and debug build**

Run:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew test
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
```

Expected: both commands exit `0`.

- [ ] **Step 4: Inspect regressions against upstream-only features**

Run:

```bash
git diff --check upstream/master...HEAD
git diff --stat upstream/master...HEAD
git status --short
```

Expected: no whitespace errors; changes are limited to the documented fork identity, Swiss layout, suggestion, personal-dictionary, input, test, generated, and document files.

- [ ] **Step 5: Commit any regenerated outputs and verification fixes**

Run:

```bash
git add res/values/layouts.xml res/xml/method.xml check_layout.output
git commit -m "build: regenerate keyboard resources"
```

Only create this commit if the generation tasks changed tracked files; otherwise do not create an empty commit.

## Plan Self-Review

- Scope coverage: Tasks 2-6 implement every approved runtime feature and the Swiss layout. Task 1 establishes the actual latest-upstream branch base and preserves the application ID. Task 7 verifies generation, tests, and build requirements.
- Placeholder scan: every implementation step names the target files, commands, and concrete behavior.
- Consistency: `CurrentlyTypedWord.Callback.currently_typed_word(String, boolean)` is introduced in Task 3 and consumed by `Suggestions` in Task 4. `UserDictionary` is introduced in Task 5 before Task 6 adds learning. The candidate model remains upstream's `MAX_COUNT == 3` words plus emoji at index `3` throughout.
