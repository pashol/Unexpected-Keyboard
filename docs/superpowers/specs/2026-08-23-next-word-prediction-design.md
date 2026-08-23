# Next-Word Prediction First Increment

**Date:** 2026-08-23
**Status:** Approved
**Target:** `Unexpected-Keyboard-upstream-port`

## Goal

Deliver opt-in, fully offline next-word prediction before adding blank-field
sentence starters. After the user enters a space, the candidate strip offers
contextual words that can be explicitly selected.

Blank-field sentence starters are intentionally deferred. Their eventual
source is local user history only, without a generic starter list or language
model fallback.

## Selected Approach

Use an AOSP LatinIME-derived n-gram decoder and immutable language packs. This
provides an established local implementation of contextual next-word scoring
and avoids extending `cdict` with new n-gram storage, ranking, and Unicode
logic. The engine remains opt-in and falls back to legacy suggestions when it
cannot be used.

## Trigger And Context

- Request next-word candidates only when the cursor is at the end of the
  editor text, no selection exists, the composing token is empty, and the
  character immediately before the cursor is whitespace.
- Extract at most three preceding words from the bounded editor context.
- Do not request a prediction when editor context is unavailable, truncated in
  a way that makes the boundary ambiguous, stale due to a cursor change, or
  otherwise invalid.
- A non-empty composing token continues through the existing completion path;
  this increment does not change completion, correction, automatic replacement,
  or emoji behavior.

## Candidate Presentation And Commit

- Adapt at most three next-word candidates into the existing three word slots
  of `Suggestions` and `CandidatesView`.
- Keep the existing separate emoji slot and personal-dictionary long-press
  removal behavior unchanged.
- Tapping a next-word candidate commits the selected word followed by exactly
  one ASCII space in a batch edit. It never deletes or replaces surrounding
  text.
- Clear next-word candidates on cursor movement, a non-empty selection,
  deletion, non-whitespace input, punctuation that is not followed by a space,
  or a stale response.

## Privacy And Failure Policy

- Never invoke next-word prediction in password, URI, email, or
  `TYPE_TEXT_FLAG_NO_SUGGESTIONS` editors.
- The decoder and language pack operate without network access; typed context
  and candidates are not logged.
- Missing or corrupt packs, decoder errors, and initialization failures clear
  experimental candidates and latch the input session back to legacy behavior.
- This milestone does not add learning or history persistence. Local-history
  sentence starters remain a later, separate feature.

## Verification

- Test preceding-word extraction for ordinary and repeated whitespace,
  punctuation, Unicode word characters, cursor movement, selections, and
  unavailable editor context.
- Test engine routing: it runs only at the specified whitespace boundary and
  discards stale results.
- Test a candidate tap commits `candidate + " "` without altering adjacent
  editor text.
- Test all excluded editor types and the fallback path.
- Add deterministic language-pack fixtures, including known bigrams such as
  `hello -> world`, to validate ranking before a production pack is used.
- Run focused tests, the full Gradle test suite, and debug and release builds.
