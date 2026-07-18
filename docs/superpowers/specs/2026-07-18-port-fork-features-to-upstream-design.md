# Port Fork Features To Latest Upstream

## Goal

Port the fork-specific runtime keyboard behavior and Swiss German layout from
`pashol/Unexpected-Keyboard` onto the latest `Julow/Unexpected-Keyboard` code,
while preserving the fork application identity and upstream's newer features.

## Scope

Included:

- Retroactive word case cycling on a Shift tap at the end of a word.
- Optional punctuation auto-spacing for `. ! ? , ; :`, with editor exclusions.
- Case-insensitive dictionary lookup for capitalized words.
- Sentence-start detection and optional capitalization of suggestions.
- Suggestion text sizing and padding improvements.
- Suggestion deduplication.
- Typed-word promotion to the center/space-bar candidate.
- Opt-in personal dictionary with learning, prioritization, and long-press removal.
- Personal-dictionary import/export through the Storage Access Framework.
- Swiss German `de_CH` QWERTZ layout and generated registration.
- Fork-specific runtime fixes that are still absent from latest upstream, including
  suggestion preference refresh behavior and disabled-suggestion autocomplete behavior.

Excluded:

- `whatsapp_to_dict.py` and other auxiliary tools.
- Layout-editor documentation and README-only changes.
- Historical upstream changes already present in the latest original repository.

The latest upstream behavior remains authoritative for unrelated functionality,
including split layouts, suggestion-entry keys, emoji suggestions, and the latest
fix preventing an extra space when entering a suggestion.

## Baseline And Identity

Implementation starts from the latest upstream `master` inspected at
`38836e440d8ca779d572b52601c6b2ad10f3bb7f` and uses a new branch named
`port-fork-features-to-upstream`.

The application ID remains `juloo.keyboard2.pashol` so the resulting build can be
installed beside the original application.

## Architecture

The port is feature-by-feature rather than a wholesale cherry-pick. The fork
commits are behavioral references, while implementation follows the latest
upstream interfaces and data flow.

### Input Behavior

The existing `Pointers` to `KeyEventHandler` to `InputConnection` path remains
unchanged. Shift at the end of a word detects the current word, cycles it through
lowercase, Title Case, and ALL CAPS, replaces the existing text, and refreshes
suggestions. Punctuation auto-spacing is controlled by a behavior preference and
is applied only to eligible editors. Password, URL, and email fields are excluded.

### Suggestions

The `CurrentlyTypedWord` callback carries sentence-start context into
`Suggestions`. The suggestion pipeline:

1. Performs case-insensitive dictionary lookup while preserving dictionary spelling.
2. Applies sentence-start capitalization when the preference is enabled.
3. Combines personal-dictionary, exact, suffix, distance, and typed-word candidates.
4. Removes duplicates before assigning upstream's current candidate slots.
5. Promotes the typed word to the center/space-bar slot unless there is exactly one
   unambiguous completion.

The implementation must retain current upstream split-layout placement and
suggestion-entry behavior.

### Personal Dictionary

The personal dictionary is opt-in and disabled by default. It stores words in
app-private storage, preserves entered capitalization, performs case-insensitive
matching, learns eligible unknown words at word boundaries, and prioritizes learned
matches. A 600 ms long press removes a personal suggestion. If autocomplete was
undone with backspace, the next delimiter learns the original typed word instead
of applying autocomplete again.

Settings expose export, import-merge, and import-replace through the Storage Access
Framework. Files are plain UTF-8 text with one word per line and require no storage
permission.

### Swiss German Layout

The fork's custom Swiss German QWERTZ XML layout is ported as a normal layout. Its
locale and dictionary registration are generated using the project's existing
layout and input-method generation tasks. Layout key restrictions remain enforced.

## Error Handling

- Dictionary or personal-dictionary lookup failures fall back to normal upstream
  suggestions and never block text entry.
- Missing, corrupt, or unreadable personal-dictionary storage is handled safely;
  explicit import actions may replace invalid content.
- Imports validate UTF-8, ignore blank lines, and report I/O failures through the
  existing settings UI.
- Merge imports retain existing words; replace imports clear the existing set first.
- Surrounding-text access remains guarded for editors that do not support it.

## Testing And Verification

Extend the existing Java unit tests for typed words, suggestions, key handling, and
layout parsing. Tests cover:

- all case-cycle states and word/cursor boundary conditions;
- punctuation spacing and excluded editor types;
- sentence-start detection and capitalization preference;
- case-insensitive lookup, deduplication, candidate ordering, and typed-word
  promotion exceptions;
- personal-dictionary learning, persistence, long-press removal, import merge,
  import replace, and autocomplete-revert behavior;
- Swiss layout registration and validation.

Before implementation is considered complete, run the generated-file checks, unit
tests, keyboard-layout validation, and a debug build using the Java environment
required by `CLAUDE.md`.

## Acceptance Criteria

- The branch is based on latest upstream rather than the fork's old `1.32.1` base.
- The fork application ID is preserved.
- Every included feature in Scope works with current upstream suggestion and layout
  architecture.
- Upstream split-layout, emoji-suggestion, suggestion-entry, and extra-space fixes
  remain intact.
- Personal dictionary is off by default and supports all three documented settings
  actions.
- Generated files are current, tests pass, layout validation passes, and the debug
  build succeeds.
