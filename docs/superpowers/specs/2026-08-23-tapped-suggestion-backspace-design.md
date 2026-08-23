# Tapped Suggestion Backspace Behavior

## Goal

Let users continue editing a word after tapping a completion in the suggestion
strip. For example, after typing `Informa` and tapping `Informatik`, Backspace
must remove only `k`, so the user can type `Informatikkenntnisse`.

## Scope

The change distinguishes deliberate candidate taps from automatic completion
with the space bar.

- A tap on a suggestion replaces the current word but does not create an
  autocomplete-undo action.
- Backspace after a tapped suggestion follows its usual behavior and deletes
  one character.
- Accepting the top suggestion with the space bar remains an autocomplete-undo
  action. Its immediate Backspace restores the word typed before completion.

## Implementation Boundary

`KeyEventHandler` owns both candidate replacement and Backspace handling. It
will record replacement state for the space-bar completion path only. The
candidate-view callback will continue to replace the typed word, but will not
arm the full-word undo state.

No changes are required to suggestion lookup, candidate display, dictionary
data, or editor integration.

## Tests

Add focused `KeyEventHandlerTest` coverage for these behaviors:

- A tapped candidate followed by Backspace deletes one character from the
  candidate.
- A space-bar accepted candidate followed by Backspace restores the original
  typed word.
- Existing direct candidate insertion continues not to add a trailing space.
