# Suggestion Improvements — Design Spec

**Date:** 2026-03-25
**Status:** Approved

---

## Problem

The suggestion strip has two related gaps:

1. **Case-insensitive lookup bug.** German (and other language) dictionaries store nouns
   capitalized (e.g. `Erde`, `Straße`). Typing `erde` (lowercase) never finds `Erde` because
   `Suggestions.query_suggestions` only tries the capitalized lookup when the user has already
   typed an uppercase first letter. Words stored capitalized are invisible to lowercase queries.

2. **No sentence-start awareness.** After `. `, `! `, `? `, `\n`, or at the start of a text
   field, the first word of the new sentence should receive capitalized suggestions. The keyboard
   has no mechanism to detect this position.

---

## Scope

- Fix the case-insensitive dictionary lookup (always-on bug fix).
- Add opt-in sentence-start capitalization (new preference, default `true`).
- Out of scope: n-gram prediction, user dictionary (separate plan already exists), distance
  search across case variants.

---

## Design

### 1. Case-insensitive lookup — `Suggestions.java`

Modify `query_suggestions(Cdict dict, String word, String[] dst, int max_count, boolean sentence_start)`.

**New lookup strategy:**

```
r_exact = dict.find(word)                          // always
r_alt   = dict.find(capitalize(word))              // NEW: when first char is lowercase
        | dict.find(word.toLowerCase())             // existing: when first char is uppercase
```

**Exact match rules (written in order into `dst`, incrementing `i` once per write):**
- If `r_exact.found` AND `r_alt` is not found or case-insensitively differs from `word` →
  write `word` as typed into `dst[i++]`.
- If `r_alt.found` → write `dict.word(r_alt.index)` as stored into `dst[i++]` (preserves
  dictionary casing, e.g. "Erde").
- If both `r_exact.found` and `r_alt.found` and they are case-insensitively equal → write
  only the stored form (`dict.word(r_alt.index)`) once, incrementing `i` once. Do not write
  the typed form at all — no post-hoc removal needed.

**Suffix traversal — trie root selection:**
- Prefer `r_exact` when it has a valid prefix node (i.e. the typed word is a prefix of at
  least one lowercase entry). This preserves lowercase completions for users typing in English
  or other all-lowercase dictionaries.
- Use `r_alt` only when `r_exact` has no valid prefix node (e.g. `erde` is not a prefix of
  anything in the lowercase subtree). This captures German noun completions (`Erdbeere`,
  `Erdbeben`) from the capitalized subtree.
- In practice: `r_for_suffixes = (r_exact.found || r_exact.prefix_ptr != 0) ? r_exact : r_alt`.

**`capitalize_results`:**
Applied to the full `dst` array (suffix and distance results alike) when
`first_char_upper || sentence_start`. For German nouns fetched via the capitalized lookup,
`dict.word()` already returns them capitalized, so `capitalize_results` is a no-op for those
entries. For all-lowercase dictionaries (e.g. English) with `sentence_start == true`,
`capitalize_results` correctly capitalizes the returned completions.

The method signature gains a `boolean sentence_start` parameter threaded in from
`currently_typed_word`.

---

### 2. Sentence-start detection — `CurrentlyTypedWord.java`

**Context window:** Two fetch sites need widening to 30 characters:
- `refresh_current_word()`: change `getTextBeforeCursor(10, 0)` → `getTextBeforeCursor(30, 0)`.
- `EditorConfig.java` (line 85): change `info.getInitialTextBeforeCursor(10, 0)` →
  `info.getInitialTextBeforeCursor(30, 0)`. This is the source for `initial_text_before_cursor`
  used by `started()`, which does not call `getTextBeforeCursor` itself.

30 characters gives reliable sentence-boundary context with negligible IPC cost increase.

**New field:** `boolean _at_sentence_start`, computed in `set_current_word()` after `_w` is
built:

```
_at_sentence_start = false                            // default; also covers selection-active
                                                      // path where set_current_word("") is called
if _w.length() == 0: return                          // no word typed; skip context check
prefix = text_before_cursor[0 .. len - _w.length()]  // text before the current word
trimmed = prefix with trailing spaces removed
if _w.length() >= 30:
    _at_sentence_start = false   // word too long to see context, default safe
else if trimmed.isEmpty():
    _at_sentence_start = true    // start of field / fetched window
else:
    last_char = trimmed.charAt(trimmed.length() - 1)
    _at_sentence_start = last_char ∈ { '.', '!', '?', '\n' }
```

**Extended callback interface:**

```java
public interface Callback {
    void currently_typed_word(String word, boolean sentence_start);
}
```

`CurrentlyTypedWord.callback()` passes `_w.toString()` and `_at_sentence_start`.
The implementer (`KeyEventHandler`) forwards both to `Suggestions.currently_typed_word`.

---

### 3. Settings & wiring

**New preference:** `capitalize_suggestions_at_sentence_start` (boolean, default `true`).

| File | Change |
|---|---|
| `Config.java` | Add `public boolean capitalize_suggestions_at_sentence_start` field; read from prefs |
| `res/xml/settings.xml` | Add `CheckBoxPreference` in `pref_category_suggestions` |
| `res/values/strings.xml` | Add title + summary strings |
| `KeyEventHandler.java` | Update `currently_typed_word(String)` → `currently_typed_word(String, boolean)` to satisfy updated `Callback` interface; forward `sentence_start` to `Suggestions` |
| `EditorConfig.java` | Widen `info.getInitialTextBeforeCursor(10, 0)` → `(30, 0)` so sentence-start detection works at keyboard-open time |
| `Suggestions.java` | `currently_typed_word(String, boolean)` — pass `sentence_start && _config.capitalize_suggestions_at_sentence_start` into `query_suggestions` |

No new files. No changes to `CandidatesView`, `Keyboard2`, or `Cdict`.

---

## Edge cases

| Case | Handling |
|---|---|
| Word ≥ 30 chars | `_at_sentence_start = false` (can't see context) |
| Both `word` and `capitalize(word)` in dict | Both found; keep stored form, discard typed form if case-insensitively equal. Suffix traversal uses `r_exact` (preferred) since the lowercase prefix is valid. |
| `r_exact` not found, `r_alt` found | Add stored form; suffix traversal uses `r_alt` (e.g. `Erde` subtree) |
| Both `r_exact` and `r_alt` not found | Formula selects `r_alt` (both have `prefix_ptr == 0`); `dict.suffixes(r_alt, n)` returns empty — same result as current behaviour |
| `sentence_start = true`, all-lowercase English dict | `capitalize_results` applied to full `dst` (suffixes + distance results) — correct for sentence start |
| `sentence_start = true`, German noun | `dict.word()` returns "Erde"; `capitalize_results` is no-op for that entry — correct |
| Preference disabled | `sentence_start` flag masked to `false` before entering `query_suggestions` |
| Selection active | `CurrentlyTypedWord` already sets `_w = ""` on selection; `_at_sentence_start = false` |

---

## Files changed

1. `srcs/juloo.keyboard2/CurrentlyTypedWord.java`
2. `srcs/juloo.keyboard2/suggestions/Suggestions.java`
3. `srcs/juloo.keyboard2/Config.java`
4. `srcs/juloo.keyboard2/KeyEventHandler.java`
5. `srcs/juloo.keyboard2/EditorConfig.java`
6. `res/xml/settings.xml`
7. `res/values/strings.xml`

---

## Verification

1. `./gradlew assembleDebug`
2. Install on device with German dictionary.
3. Type `erde` → `Erde`, `Erdbeere` etc. appear in suggestion strip.
4. Type `das ist gut. ` then start a word → suggestions are capitalised.
5. Disable preference → suggestions at sentence start are no longer capitalised.
6. Type a long word (> 30 chars) → no crash, suggestions still appear.
