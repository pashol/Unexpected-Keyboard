# Suggestion Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix case-insensitive dictionary lookup so German nouns like `Erde` appear when typing `erde`, and add opt-in sentence-start capitalization of suggestions.

**Architecture:** `CurrentlyTypedWord` gains sentence-start detection (static helper + boolean field); the `Callback` interface passes this flag downstream through `KeyEventHandler` to `Suggestions`, which uses it in `query_suggestions` alongside a new bidirectional capitalization lookup. A new user preference gates the sentence-start behaviour.

**Tech Stack:** Java 8, Android IME API, JUnit 4 for unit tests, Cdict native dictionary library (JNI).

**Spec:** `docs/superpowers/specs/2026-03-25-suggestion-improvements-design.md`

---

## File Map

| File | Change |
|---|---|
| `srcs/juloo.keyboard2/EditorConfig.java` | Widen `getInitialTextBeforeCursor(10,0)` → `(30,0)` |
| `srcs/juloo.keyboard2/CurrentlyTypedWord.java` | Widen `getTextBeforeCursor(10,0)` → `(30,0)`; add `_at_sentence_start` field + `sentence_start_from_context` static helper; extend `Callback` |
| `srcs/juloo.keyboard2/KeyEventHandler.java` | Implement updated `Callback` signature |
| `srcs/juloo.keyboard2/suggestions/Suggestions.java` | New `currently_typed_word` signature; rewrite `query_suggestions` |
| `srcs/juloo.keyboard2/Config.java` | Add `capitalize_suggestions_at_sentence_start` field |
| `res/xml/settings.xml` | Add `CheckBoxPreference` in suggestions category |
| `res/values/strings.xml` | Add 2 string resources |
| `test/juloo.keyboard2/CurrentlyTypedWordTest.java` | New — unit tests for `sentence_start_from_context` |

---

## Task 1: Widen text-before-cursor fetch windows

**Files:**
- Modify: `srcs/juloo.keyboard2/EditorConfig.java:85`
- Modify: `srcs/juloo.keyboard2/CurrentlyTypedWord.java:116`

- [ ] **Step 1: Edit EditorConfig.java**

In `EditorConfig.java` line 85, change the fetch limit from `10` to `30`:

```java
// Before:
initial_text_before_cursor = info.getInitialTextBeforeCursor(10, 0);
// After:
initial_text_before_cursor = info.getInitialTextBeforeCursor(30, 0);
```

- [ ] **Step 2: Edit CurrentlyTypedWord.java**

In `CurrentlyTypedWord.java` line 116, change the fetch limit:

```java
// Before:
set_current_word(_ic.getTextBeforeCursor(10, 0));
// After:
set_current_word(_ic.getTextBeforeCursor(30, 0));
```

- [ ] **Step 3: Build to confirm no compile errors**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" PATH="/c/Program Files/Android/Android Studio/jbr/bin:$PATH" ./gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add srcs/juloo.keyboard2/EditorConfig.java srcs/juloo.keyboard2/CurrentlyTypedWord.java
git commit -m "Widen text-before-cursor fetch window from 10 to 30 chars"
```

---

## Task 2: Add sentence-start detection to CurrentlyTypedWord

**Files:**
- Modify: `srcs/juloo.keyboard2/CurrentlyTypedWord.java`
- Create: `test/juloo.keyboard2/CurrentlyTypedWordTest.java`

- [ ] **Step 1: Write the failing unit test**

Create `test/juloo.keyboard2/CurrentlyTypedWordTest.java`:

```java
package juloo.keyboard2;

import org.junit.Test;
import static org.junit.Assert.*;
import static juloo.keyboard2.CurrentlyTypedWord.sentence_start_from_context;

public class CurrentlyTypedWordTest
{
  @Test
  public void field_start_is_sentence_start()
  {
    // Word fills the entire fetched text → nothing before it → field start
    assertTrue(sentence_start_from_context("hello", 5));
  }

  @Test
  public void after_period_space_is_sentence_start()
  {
    assertTrue(sentence_start_from_context("das ist gut. h", 1));
  }

  @Test
  public void after_exclamation_is_sentence_start()
  {
    assertTrue(sentence_start_from_context("gut! h", 1));
  }

  @Test
  public void after_question_is_sentence_start()
  {
    assertTrue(sentence_start_from_context("gut? he", 2));
  }

  @Test
  public void after_newline_is_sentence_start()
  {
    assertTrue(sentence_start_from_context("gut\nhe", 2));
  }

  @Test
  public void mid_sentence_is_not_sentence_start()
  {
    assertFalse(sentence_start_from_context("das ist erd", 3));
  }

  @Test
  public void comma_is_not_sentence_start()
  {
    assertFalse(sentence_start_from_context("ja, na", 2));
  }

  @Test
  public void empty_word_is_not_sentence_start()
  {
    // Covers selection-active path: set_current_word("") → word_length=0
    assertFalse(sentence_start_from_context("", 0));
  }

  @Test
  public void word_too_long_is_not_sentence_start()
  {
    // word_length >= 30 → can't see context → false
    String longWord = "abcdefghijklmnopqrstuvwxyzabcde"; // 31 chars
    assertFalse(sentence_start_from_context(longWord, 31));
  }

  @Test
  public void multiple_spaces_before_word_still_detects_sentence_start()
  {
    // Period followed by two spaces before word
    assertTrue(sentence_start_from_context("gut.  wo", 2));
  }
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" PATH="/c/Program Files/Android/Android Studio/jbr/bin:$PATH" ./gradlew test --tests "juloo.keyboard2.CurrentlyTypedWordTest"
```
Expected: FAIL — `sentence_start_from_context` does not exist yet.

- [ ] **Step 3: Add `sentence_start_from_context` static helper and `_at_sentence_start` field**

In `CurrentlyTypedWord.java`, add the new field after `_refresh_pending` (around line 25):

```java
/** Whether the cursor is at the start of a sentence (after . ! ? \n or at field start). */
boolean _at_sentence_start = false;
```

Add the static helper method before the `Callback` interface (end of class):

```java
/** Pure helper — computes sentence-start from fetched text and current word length.
    Package-private for unit testing. */
static boolean sentence_start_from_context(String text, int word_length)
{
  if (word_length == 0 || word_length >= 30)
    return false;
  int prefix_len = text.length() - word_length;
  if (prefix_len < 0)
    return false;
  // Scan backwards past spaces to find the last non-space character
  int i = prefix_len - 1;
  while (i >= 0 && text.charAt(i) == ' ')
    i--;
  if (i < 0)
    return true; // Nothing before the word — field start or start of fetched window
  char c = text.charAt(i);
  return c == '.' || c == '!' || c == '?' || c == '\n';
}
```

Update `set_current_word` to compute `_at_sentence_start`. Replace the existing method body.

> **Note:** `_at_sentence_start` is only recomputed inside `set_current_word`. The `typed()` path calls `callback()` directly (no IPC), so the flag stays at its last computed value between `set_current_word` calls. A `delayed_refresh` fires ~50 ms after key events to re-sync. This is intentional — recomputing on every keystroke would require an IPC call each time.

```java
void set_current_word(CharSequence text_before_cursor)
{
  _w.setLength(0);
  _at_sentence_start = false;
  if (text_before_cursor == null)
    return;
  int saved_cursor = _cursor;
  String text = text_before_cursor.toString();
  type_chars(text);
  _cursor = saved_cursor;
  _at_sentence_start = sentence_start_from_context(text, _w.length());
  callback();
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" PATH="/c/Program Files/Android/Android Studio/jbr/bin:$PATH" ./gradlew test --tests "juloo.keyboard2.CurrentlyTypedWordTest"
```
Expected: all 10 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add srcs/juloo.keyboard2/CurrentlyTypedWord.java test/juloo.keyboard2/CurrentlyTypedWordTest.java
git commit -m "Add sentence-start detection to CurrentlyTypedWord"
```

---

## Task 3: Thread sentence_start through the callback chain

This task updates three files together — they must all compile as a unit since the `Callback` interface signature changes.

**Files:**
- Modify: `srcs/juloo.keyboard2/CurrentlyTypedWord.java`
- Modify: `srcs/juloo.keyboard2/KeyEventHandler.java`
- Modify: `srcs/juloo.keyboard2/suggestions/Suggestions.java`

- [ ] **Step 1: Extend `Callback` interface in CurrentlyTypedWord**

Replace the `Callback` interface (last lines of `CurrentlyTypedWord.java`):

```java
// Before:
public static interface Callback
{
  public void currently_typed_word(String word);
}

// After:
public static interface Callback
{
  public void currently_typed_word(String word, boolean sentence_start);
}
```

Update `callback()` to pass `_at_sentence_start`:

```java
// Before:
void callback()
{
  _callback.currently_typed_word(_w.toString());
}

// After:
void callback()
{
  _callback.currently_typed_word(_w.toString(), _at_sentence_start);
}
```

- [ ] **Step 2: Update KeyEventHandler to satisfy the new interface**

In `KeyEventHandler.java`, replace the `currently_typed_word` override (around line 170):

```java
// Before:
@Override
public void currently_typed_word(String word)
{
  _suggestions.currently_typed_word(word);
}

// After:
@Override
public void currently_typed_word(String word, boolean sentence_start)
{
  _suggestions.currently_typed_word(word, sentence_start);
}
```

- [ ] **Step 3: Update Suggestions to accept the new parameter**

In `Suggestions.java`, update `currently_typed_word` to accept and forward `sentence_start`.
Also add `sentence_start` parameter to `query_suggestions`. The `sentence_start` flag is passed
through immediately — Task 5 adds the preference mask so it can be disabled. Note: `_at_sentence_start`
is computed only inside `set_current_word`, not on every `typed()` keystroke; a 50 ms `delayed_refresh`
keeps it current between explicit refreshes — this is an intentional trade-off to avoid IPC on every key.

```java
// Replace the existing currently_typed_word method:
public void currently_typed_word(String word, boolean sentence_start)
{
  Cdict dict = _config.current_dictionary;
  if (word.length() < 2 || dict == null)
  {
    set_suggestions(NO_SUGGESTIONS);
  }
  else
  {
    String[] dst = new String[3];
    query_suggestions(dict, word, dst, 3, sentence_start);
    set_suggestions(Arrays.asList(dst));
  }
}
```

Add `boolean sentence_start` to `query_suggestions` signature (keep body unchanged for now — Task 4 rewrites it):

```java
// Before:
int query_suggestions(Cdict dict, String word, String[] dst, int max_count)

// After:
int query_suggestions(Cdict dict, String word, String[] dst, int max_count, boolean sentence_start)
```

- [ ] **Step 4: Build to confirm everything compiles**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" PATH="/c/Program Files/Android/Android Studio/jbr/bin:$PATH" ./gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add srcs/juloo.keyboard2/CurrentlyTypedWord.java srcs/juloo.keyboard2/KeyEventHandler.java srcs/juloo.keyboard2/suggestions/Suggestions.java
git commit -m "Thread sentence_start flag through Callback chain to Suggestions"
```

---

## Task 4: Fix case-insensitive lookup in query_suggestions

**Files:**
- Modify: `srcs/juloo.keyboard2/suggestions/Suggestions.java`

No unit test is possible here without a real Cdict binary (native library). Correctness is verified by building and on-device testing in Task 6.

- [ ] **Step 1: Rewrite query_suggestions and fix capitalize_results**

Replace the full `query_suggestions` method in `Suggestions.java`.
Also replace `capitalize_results` with a Unicode-safe version using `Utils.capitalize_string`.
Add `import juloo.keyboard2.Utils;` at the top of the file if not already present.

Replace `capitalize_results`:
```java
void capitalize_results(String[] rs)
{
  for (int i = 0; i < rs.length; i++)
    if (rs[i] != null)
      rs[i] = juloo.keyboard2.Utils.capitalize_string(rs[i]);
}
```

```java
int query_suggestions(Cdict dict, String word, String[] dst, int max_count, boolean sentence_start)
{
  Cdict.Result r_exact = dict.find(word);
  boolean first_char_upper = Character.isUpperCase(word.charAt(0));
  // Also try the opposite-first-char form so that:
  //   - German nouns (stored as "Erde") are found when typing "erde"
  //   - Lowercase forms ("the") are found when typing "The"
  String alt_word = first_char_upper
    ? word.toLowerCase()
    : juloo.keyboard2.Utils.capitalize_string(word);
  Cdict.Result r_alt = dict.find(alt_word);

  int i = 0;
  // Write exact match — but skip if r_alt produces the same word (prevents duplicates)
  if (r_exact.found)
  {
    if (!r_alt.found || !word.equalsIgnoreCase(dict.word(r_alt.index)))
      dst[i++] = word;
  }
  // Write alt match as stored in dictionary (preserves correct casing, e.g. "Erde")
  if (r_alt.found && i < max_count)
    dst[i++] = dict.word(r_alt.index);

  // Suffix traversal: prefer r_exact when it has a valid prefix node (covers lowercase
  // completions). Fall back to r_alt only when r_exact has no usable prefix (e.g. "erde"
  // is not a prefix of any lowercase entry, but "Erde" subtree has "Erdbeere" etc.)
  Cdict.Result r_for_suffixes = (r_exact.found || r_exact.prefix_ptr != 0) ? r_exact : r_alt;
  int[] suffixes = dict.suffixes(r_for_suffixes, max_count);
  // Disable distance search for small words
  int[] dist = (word.length() < 3 || i + 1 >= max_count) ? NO_RESULTS :
    dict.distance(word, 1, max_count);
  for (int j = 0; j < max_count && i < max_count; j++)
  {
    if (suffixes.length > j)
      dst[i++] = dict.word(suffixes[j]);
    if (dist.length > j && i < max_count)
      dst[i++] = dict.word(dist[j]);
  }
  // Capitalize the full dst array when user typed a capital or cursor is at sentence start
  if (first_char_upper || sentence_start)
    capitalize_results(dst);
  return i;
}
```

- [ ] **Step 2: Build**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" PATH="/c/Program Files/Android/Android Studio/jbr/bin:$PATH" ./gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add srcs/juloo.keyboard2/suggestions/Suggestions.java
git commit -m "Fix case-insensitive dict lookup to find capitalized nouns (e.g. Erde)"
```

---

## Task 5: Add preference and wire into Suggestions

**Files:**
- Modify: `srcs/juloo.keyboard2/Config.java`
- Modify: `res/xml/settings.xml`
- Modify: `res/values/strings.xml`
- Modify: `srcs/juloo.keyboard2/suggestions/Suggestions.java`

- [ ] **Step 1: Add field to Config.java**

After `auto_space_after_punct` (around line 69), add:

```java
public boolean capitalize_suggestions_at_sentence_start;
```

In the `refresh()` method, after the `auto_space_after_punct` line (around line 189), add:

```java
capitalize_suggestions_at_sentence_start = _prefs.getBoolean("capitalize_suggestions_at_sentence_start", true);
```

- [ ] **Step 2: Add CheckBoxPreference to settings.xml**

In `res/xml/settings.xml`, inside `pref_category_suggestions`, add after the `space_bar_auto_complete` preference (after line 17):

```xml
<CheckBoxPreference
    android:key="capitalize_suggestions_at_sentence_start"
    android:title="@string/pref_capitalize_suggestions_at_sentence_start_title"
    android:summary="@string/pref_capitalize_suggestions_at_sentence_start_summary"
    android:defaultValue="true"/>
```

- [ ] **Step 3: Add string resources to strings.xml**

In `res/values/strings.xml`, add alongside the other suggestions strings (near `pref_space_bar_auto_complete_*`):

```xml
<string name="pref_capitalize_suggestions_at_sentence_start_title">Capitalize suggestions at sentence start</string>
<string name="pref_capitalize_suggestions_at_sentence_start_summary">Show capitalized suggestions after . ! ? or at the start of a field</string>
```

- [ ] **Step 4: Wire preference into Suggestions.currently_typed_word**

In `Suggestions.java`, update `currently_typed_word` to mask `sentence_start` with the preference:

```java
public void currently_typed_word(String word, boolean sentence_start)
{
  Cdict dict = _config.current_dictionary;
  if (word.length() < 2 || dict == null)
  {
    set_suggestions(NO_SUGGESTIONS);
  }
  else
  {
    String[] dst = new String[3];
    boolean effective_sentence_start = sentence_start
      && _config.capitalize_suggestions_at_sentence_start;
    query_suggestions(dict, word, dst, 3, effective_sentence_start);
    set_suggestions(Arrays.asList(dst));
  }
}
```

- [ ] **Step 5: Build**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" PATH="/c/Program Files/Android/Android Studio/jbr/bin:$PATH" ./gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Run all unit tests**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" PATH="/c/Program Files/Android/Android Studio/jbr/bin:$PATH" ./gradlew test
```
Expected: all tests PASS (including the new `CurrentlyTypedWordTest`).

- [ ] **Step 7: Commit**

```bash
git add srcs/juloo.keyboard2/Config.java res/xml/settings.xml res/values/strings.xml srcs/juloo.keyboard2/suggestions/Suggestions.java
git commit -m "Add capitalize-suggestions-at-sentence-start preference"
```

---

## Task 6: On-device verification

- [ ] **Step 1: Install on a connected device or emulator**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" PATH="/c/Program Files/Android/Android Studio/jbr/bin:$PATH" ./gradlew installDebug
```

- [ ] **Step 2: German noun lookup (the core bug fix)**

1. Install the German dictionary via Settings → Dictionaries.
2. Open any text field with the keyboard active.
3. Type `erde` (all lowercase).
4. Expected: `Erde`, `Erdbeere`, or other German noun completions appear in the suggestion strip.
5. Type `Erde` (capital E).
6. Expected: still works — `Erde` appears as before.

- [ ] **Step 3: Sentence-start capitalization**

1. Go to Settings → Suggestions → confirm "Capitalize suggestions at sentence start" is on (default).
2. Type a sentence ending with a period and space: `das ist gut. `
3. Start typing a lowercase word, e.g. `d`.
4. Expected: suggestions are capitalized (e.g. `Das`, `Der`, `Die`).
5. Type mid-sentence without preceding punctuation.
6. Expected: suggestions are lowercase (e.g. `das`, `der`, `die`).

- [ ] **Step 4: Verify preference toggle**

1. Go to Settings → Suggestions → disable "Capitalize suggestions at sentence start".
2. Repeat step 3 from above.
3. Expected: suggestions at sentence start are now lowercase — preference is respected.

- [ ] **Step 5: Edge case — long word**

1. Type a word longer than 30 characters (e.g. paste `Rindfleischetikettierungsüberwachungsaufgaben`).
2. Expected: no crash, suggestions still appear (may be empty if word not in dict).
