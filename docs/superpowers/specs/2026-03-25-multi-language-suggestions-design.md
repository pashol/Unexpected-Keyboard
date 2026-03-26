# Multi-Language Suggestions Design

**Date:** 2026-03-25
**Status:** Approved

---

## Context

The app currently supports only one active dictionary at a time. The active dictionary is determined by the Android IME *subtype* — a system-level concept the user changes via Android's language/keyboard switcher, not within the app itself. This means:

- Switching layouts within the app does **not** change the dictionary
- Users who write in multiple languages on a single layout (e.g. German with English technical terms) never get useful suggestions, because only one language's dictionary is loaded
- The only workaround is switching the Android IME subtype, which is a cumbersome system-level action

The goal is to let users configure which languages they write in, and have the keyboard automatically surface suggestions from the right language as they type — without requiring any manual language switching.

---

## Design

### Overview

1. The user configures **Active dictionaries** (multi-select) — which languages to consider for suggestions.
2. The user configures a **Default language** (single-select from the active set) — used as the preferred language when there is no other signal.
3. A **combined ranking algorithm** selects and orders suggestions per word:
   - **Quality ranking** (primary): query all active dicts, rank by match quality. The dict with the best match for *this word* wins.
   - **Language momentum** (tiebreaker): a sliding window of the last 8 confirmed words tracks which dict recently produced the best matches. When quality is equal, momentum breaks the tie — reducing flickering on ambiguous short words.
   - **Default language** (initialization): in a new text field with no momentum history, the default language gets a baseline momentum advantage, ensuring immediate correct-language suggestions without a warm-up period.

### Why not pure sliding window detection?

The sliding window alone has a circularity problem: the detector uses the dictionary to identify the language, but the dictionary only helps if you already know the language. Ambiguous short words ("in", "an", "no") exist in multiple languages; unknown words (names, URLs, technical terms) match no dictionary. Per-word quality ranking sidesteps this entirely — each word independently gets the best match from any active dict.

---

## Algorithm

### Per-Word Quality Ranking

For each typed partial word:
1. Query all active dicts; collect each dict's top candidates with their rank.
2. Determine "best match quality" per dict:
   - **Exact match** = highest quality
   - **Prefix match** (typed chars are a prefix of the candidate) = medium
   - **No match** = lowest
3. The dict with the highest quality match leads. If one dict has an exact/prefix match and another does not, quality decides — momentum is not consulted.

### Language Momentum (Tiebreaker)

- Maintain a circular buffer of size `WINDOW_SIZE = 8` confirmed words.
- After each word is confirmed (space/punctuation typed, or suggestion tapped):
  - Find which active dict had the highest-quality match for that completed word.
  - Record a hit for that dict in the buffer.
- **Momentum score** per dict = `hits / WINDOW_SIZE` (0.0–1.0).
- When two dicts have **equal quality** for the current partial word, the dict with higher momentum ranks first.
- If no word in the buffer matched any dict (e.g. proper nouns only), momentum stays unchanged.

### Default Language (Initialization)

- On `Suggestions.reset()` (new text field, IME hidden/shown): clear the buffer; give the default language dict a synthetic momentum of `INITIAL_MOMENTUM = 0.5`; all other dicts start at 0.
- This gives the default language tiebreaker priority immediately, without needing prior word history.

### Candidate Merging

After ranking by quality + momentum:
1. Take the top dict's candidates first.
2. Append candidates from other dicts that don't duplicate any already-included result (case-insensitive key comparison, preserve original casing).
3. Return up to 3 total candidates.

---

## Preferences

Two new preferences added to the **Suggestions** section in settings:

### 1. Active dictionaries (`pref_active_dictionaries`)
- Type: `MultiSelectListPreference`
- Entries: populated at runtime from `Dictionaries.getInstalledNames()`
- Stored as: `Set<String>` in SharedPreferences
- Default: the current subtype's dictionary (backward-compat migration for existing users)
- Disabled with explanatory summary if no dictionaries are installed

### 2. Default language (`pref_default_language`)
- Type: `ListPreference`
- Entries: same set as the active dictionaries selection (updated dynamically)
- Stored as: `String` in SharedPreferences
- Default: first element of active dicts (alphabetical), or the subtype's dict
- If the current default is removed from the active set, reset to the first remaining active dict

---

## Components

### `Config.java`
- Remove: `current_dictionary: Cdict`
- Add: `current_dictionaries: NamedDict[]` — array of `{name: String, dict: Cdict}` for all loaded active dicts
- Add: `default_dictionary_name: String` — name of the default-language dict
- `apply_preferences()` reads both new prefs; Keyboard2 triggers dict reload on change

### `Keyboard2.java`
- Replace `refresh_current_dictionary()` with `refresh_current_dictionaries()`:
  - Reads `_config.active_dictionary_names` and `_config.default_dictionary_name`
  - Calls `_dictionaries.load(name)` for each active name, populates `_config.current_dictionaries`
  - **Fallback**: if active set is empty/null, uses current subtype's dictionary (backward compat)
- Call `refresh_current_dictionaries()` on preference change and on subtype change

### `suggestions/Suggestions.java`
- Add inner class `LanguageMomentum`:
  - `String[] _buffer` — circular buffer of which dict name "won" each of the last 8 words
  - `int _head` — circular buffer head
  - `float getMomentum(String dictName)` — hits / WINDOW_SIZE
  - `void recordWin(String dictName)` — write to buffer, advance head
  - `void reset(String defaultDictName)` — fill buffer with `null`, set `_default` for the initial boost
- Modify `currently_typed_word(word, sentence_start)`:
  - For each active dict, query candidates and note best match quality
  - Combine quality + momentum to rank dicts
  - Merge candidates from top → lower dicts, deduplicate, return top 3
- Add `record_confirmed_word(String word)`:
  - Find which active dict had the best match for the completed word
  - Call `_momentum.recordWin(dictName)` (no-op if no dict matched)
  - Called when word boundary is detected (before the new partial word query)
- Modify `reset()`: call `_momentum.reset(defaultDictName)`

### `dict/Dictionaries.java`
- Add: `Set<String> getInstalledNames()` — returns `_installed_dictionaries` for the preference UI

### `res/xml/preferences.xml`
- Add `MultiSelectListPreference` for `pref_active_dictionaries` under Suggestions category
- Add `ListPreference` for `pref_default_language` under Suggestions category (below active dicts)

### Preferences activity/fragment (Java)
- On `pref_active_dictionaries` change: update `pref_default_language` entries; if current default no longer active, reset to first remaining
- Populate both preferences' entries from `Dictionaries.getInstalledNames()` at display time

---

## Data Flow

```
User types character
       ↓
CurrentlyTypedWord detects word boundary (space/punctuation)
       ↓
Suggestions.record_confirmed_word(completed_word)
  → query each active dict for best match on completed_word
  → LanguageMomentum.recordWin(winning_dict_name)
       ↓
Suggestions.currently_typed_word(partial_word, sentence_start)
  → query all active dicts, collect candidates + quality
  → rank dicts: quality first, momentum tiebreaker
  → merge candidates: top dict first, fill with non-duplicates from others
  → top 3 results → CandidatesView.set_candidates(...)
```

---

## Edge Cases

| Scenario | Behavior |
|----------|----------|
| No dicts installed | Suggestions disabled (same as today); new prefs hidden/disabled |
| Only one dict active | No momentum tracking needed; same behavior as today |
| Word in multiple dicts equally | Momentum (or default language at field start) breaks tie |
| Unknown word / proper noun | No dict matches; no suggestion for this word; momentum unchanged |
| Default language removed from active set | Default resets to first remaining active dict |
| Empty active set after pref change | Treat as "no dicts installed" |
| New field opened | `reset()` called; momentum clears; default language gets 0.5 initial momentum |
| Suggestion tapped from strip | `record_confirmed_word()` called for the accepted word |

---

## Verification

1. **Install two dictionaries** (e.g. English + German) via the app's dictionary manager.
2. **Settings → Suggestions**: verify "Active dictionaries" shows both; select both.
3. **Set default language** to German.
4. **Open a new text field**: type `"Hal"` → German "Hallo" should appear immediately (default language, good quality match).
5. **Type several English words** (e.g. "the project is done "): after a few words, type `"th"` → suggestions should be English ("the", "then", "they").
6. **Type a few German words** (e.g. "Das ist gut "): suggestions shift back to German.
7. **Ambiguous word test**: type `"in"` early in a new field (default = German) → German result appears first; type `"in"` after several English words → English appears first.
8. **Open a new text field**: suggestions revert to default language (German) immediately.
9. **Set only one active dict**: verify suggestions work as before (no regression).
10. **Remove all active dicts**: verify no crashes and suggestions strip disappears.
11. **Run tests**: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew test` — no regressions.
