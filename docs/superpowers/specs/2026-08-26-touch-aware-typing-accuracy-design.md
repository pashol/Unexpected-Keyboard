# Touch-Aware Typing Accuracy Design

**Date:** 2026-08-26
**Status:** Approved
**Target:** `Unexpected-Keyboard-upstream-port`

## Goal

Improve suggestions for words containing neighboring-key tap errors without
changing the characters committed by individual taps. The first increment
supports the existing `en`, `de`, `de-CH`, and `gsw` LatinIME language packs,
uses fixed keyboard geometry, and remains part of the opt-in experimental
prediction mode.

Automatic replacement, raw tap remapping, personal tap calibration, glide word
typing, swipe-symbol recognition changes, and suggestion-strip redesign are
outside this increment.

## Product Research

Public product behavior shows a common layered approach:

- Gboard combines typo suggestions, configurable autocorrection, immediate
  backspace undo, personalization, and glide typing.
- Microsoft SwiftKey combines a prediction bar, space-triggered autocorrection,
  multilingual input, and personalization.
- Apple Keyboard exposes predictive completions and inline corrections while
  retaining explicit rejection and undo controls.
- AOSP LatinIME provides the relevant open implementation foundation:
  keyboard proximity, touch-position correction, lexical and contextual
  scoring, and candidate generation.

Unexpected Keyboard currently resolves taps through rigid rectangular logical
key regions. Its legacy completion path uses exact, prefix, suffix, and
edit-distance-one searches without touch coordinates. The vendored LatinIME
decoder contains proximity support, but the application currently uses it only
for context-only next-word requests and does not pass keyboard geometry or
touch samples.

## Selected Approach

Feed composing-word touch coordinates and active keyboard geometry into the
vendored LatinIME decoder. Keep the literal committed characters unchanged and
use touch proximity only to rank explicit suggestions.

This approach reuses a mature decoder, accounts for the user's actual touch
locations, and fits the existing experimental prediction architecture. A
keyboard-neighbor weighted edit distance in `cdict` would duplicate decoder
logic and ignore exact touch positions. Changing raw hit testing would commit
contextually guessed characters and conflict with the explicit-suggestion
requirement.

## Architecture

Extend the existing experimental LatinIME path from context-only next-word
prediction to current-word completion and correction. Keep the legacy
`Suggestions` and `cdict` path unchanged when experimental mode is disabled or
unavailable.

### `TouchSequence`

An immutable composing-word input containing literal code points and one
normalized touch coordinate for each directly tapped main character. It does
not manufacture coordinates for pasted text, programmatic input, hardware-key
input, or symbols selected through per-key gestures.

Coordinates are normalized against the geometry snapshot used when the tap was
captured. Touch samples are transient session data and are never logged or
persisted.

### `KeyboardProximity`

An immutable snapshot derived from the active `KeyboardData` geometry. It
contains each main character's logical bounds and center in the coordinate
system expected by the decoder. It supports shifted, staggered, variable-width,
split, portrait, and landscape layouts.

The snapshot carries a geometry generation. Rebuild it when the active layout,
measured dimensions, orientation, split transformation, or relevant margins
change.

### `PredictionRequest`

Expand the model-facing request to distinguish current-word and next-word
prediction. A current-word request contains:

- The literal composing token and cursor position.
- Up to three preceding context words.
- The active locale and result limit.
- An optional complete `TouchSequence` and matching `KeyboardProximity`.
- Request and geometry generations for stale-result rejection.

Next-word requests retain their existing context-only behavior.

### Existing Components

`Keyboard2View` continues to select and commit the same literal key as today.
It additionally reports the selected main key, down coordinate, and geometry
generation through the input path.

`CurrentlyTypedWord` owns synchronization between editor composing text and
touch samples. It exposes touch evidence only when the composing token's code
points align exactly with the captured sequence. It invalidates uncertain
evidence instead of guessing.

The LatinIME adapter translates `KeyboardProximity`, token code points, touch
coordinates, and preceding context into a bounded native decoder request. The
decoder combines lexical frequency, n-gram context, edit cost, and touch
distance.

`Suggestions` adapts at most three ranked results into the existing candidate
slots. It preserves capitalization behavior, deduplicates candidates
case-insensitively, and keeps the literal typed word available so the user can
reject every proposed correction explicitly.

## Input And Candidate Flow

1. On a main-character `ACTION_DOWN`, capture the selected key, raw touch
   position, and current geometry generation.
2. Append a normalized sample to the active `TouchSequence` only after that
   character is successfully committed.
3. Refresh the composing token from the editor and align its code points with
   the captured sequence.
4. For each eligible token of at least two characters, issue a synchronous,
   generation-tagged current-word request. Use touch-aware scoring only when
   token, sequence, and geometry generations match exactly.
5. Fall back to text-only LatinIME scoring when the token is valid but touch
   evidence is absent or invalid.
6. Rank and deduplicate candidates, preserve the literal token, and display at
   most three results in the existing strip.
7. A correction candidate tap uses the existing word-replacement flow. Space
   commits the literal text unchanged.
8. After whitespace, the existing next-word prediction flow resumes unchanged.

Gesture-produced symbols and non-character keys do not receive fabricated
touch evidence. Clear or invalidate affected samples after cursor movement,
selection changes, editor restart, layout or size changes, candidate
replacement, external editor changes, or a deletion that cannot be mapped
unambiguously.

## Privacy And Failure Policy

- Apply the existing prediction editor policy. Do not collect coordinates or
  request correction in password, URI, email, `NO_SUGGESTIONS`, or other
  excluded editors.
- Keep all correction processing offline. Do not log or persist typed text,
  touch coordinates, or candidate contents.
- If geometry is incomplete, touch alignment is stale, or input origin is
  uncertain, use text-only LatinIME scoring.
- If LatinIME initialization, native decoding, or the language pack fails,
  retain the existing session-latched fallback to legacy suggestions.
- Key commitment must not wait for prediction. A failed or over-budget request
  clears experimental candidates and leaves literal input untouched.
- Do not add a user-visible setting beyond the existing experimental prediction
  toggle.

## Evaluation And Release Gates

Build deterministic typo corpora for `en`, `de`, `de-CH`, and `gsw`. Each
corpus includes adjacent-key substitution scenarios, exact valid words,
capitalization cases, and language-specific forms. The Swiss German corpus must
include valid regional variants so proximity scoring does not normalize dialect
spellings into Standard German.

Compare touch-aware and text-only LatinIME ranking using:

- Top-one and top-three recall on adjacent-key typo cases.
- Mean reciprocal rank.
- Exact-word retention on valid input.
- p50, p95, and p99 prediction latency.
- Native and Java memory stability across repeated sessions.

Release this increment within experimental mode only when:

- Top-three recall improves by at least 10% relatively on the combined
  adjacent-key typo benchmark.
- Each supported locale shows a non-negative top-three recall change; gains in
  one language cannot hide a regression in another.
- Exact-word retention regresses by no more than 0.5 percentage points overall
  and by no more than 1 percentage point in any supported locale.
- Prediction latency remains below 15 ms at p95 and 30 ms at p99 on the oldest
  supported test device.
- Repeated sessions show no unbounded Java or native-memory growth.
- Sensitive and excluded editors produce no correction request or touch
  collection.

## Verification

- Geometry unit tests cover staggered, shifted, variable-width, split,
  portrait, and landscape layouts, including generation changes.
- Touch-sequence unit tests cover ordinary taps, backspace, cursor edits,
  selections, gestures, candidate acceptance, external edits, and stale
  geometry.
- Request-routing tests verify touch-aware, text-only, next-word, disabled, and
  legacy-fallback paths.
- Native integration fixtures prove that a tap near a neighboring key promotes
  the intended correction without changing literal input.
- Candidate tests verify typed-word preservation, capitalization,
  case-insensitive deduplication, deterministic ties, and the three-slot limit.
- Privacy tests verify that excluded editors neither collect coordinates nor
  invoke correction.
- Device benchmarks report latency and memory against the release gates.

## Source References

- Gboard correction help: https://support.google.com/gboard/answer/7068415
- Gboard glide typing: https://support.google.com/gboard/answer/2811346
- Microsoft SwiftKey help: https://support.microsoft.com/en-us/swiftkey
- Apple predictive text: https://support.apple.com/guide/iphone/use-predictive-text-iphd4ea90231/ios
- AOSP LatinIME: https://android.googlesource.com/platform/packages/inputmethods/LatinIME/
