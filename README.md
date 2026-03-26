# Unexpected Keyboard — pashol fork

Fork of [Julow/Unexpected-Keyboard](https://github.com/Julow/Unexpected-Keyboard) at v1.32.1.

## Fork-specific additions

**New layouts**
- Swiss German (de_CH) QWERTZ layout with accent keys and € symbol

**Bug fixes**
- Fix autocomplete firing when suggestions are disabled
- Fix suggestions preferences (auto-space, auto-capitalize) not taking effect until keyboard restart
- Fix UTF-8 encoding when reading compose sequence files on Windows

**New features**
- **Retroactive word case cycling**: tap Shift at end of a word to cycle lowercase → Title Case → ALL CAPS → lowercase
- **Auto-space after punctuation** (. ! ? , ; : ): automatically inserts a space; suppressed in password/URL/email fields; toggleable in Settings › Behavior
- **Case-insensitive suggestion lookup**: typing a lowercase word now finds dictionary entries stored with a capital first letter (e.g. typing `erde` surfaces `Erde` and `Erdbeere` from a German dictionary)
- **Sentence-start capitalization**: suggestions are automatically capitalized after `. `, `! `, `? `, `\n`, or at the start of a text field; toggleable in Settings › Suggestions (on by default)
- **Suggestion strip improvements**: text auto-shrinks to fit on one line (no more wrapping for long words); horizontal padding between candidates for readability
- **Deduplicated suggestions**: suffix completions and distance matches are checked against already-shown candidates so the same word never appears twice in the strip
- **Personal dictionary**: opt-in feature (Settings › Suggestions) that learns words you type and surfaces them first in the strip; hold 600 ms on a personal suggestion to remove it; backspace after an autocomplete then space/punctuation commits and learns the original typed word instead of auto-completing again

## Related tools

### Keyboard Layout Editor

[**uk-layout-editor.vercel.app**](https://uk-layout-editor.vercel.app/) — a visual editor for creating and modifying Unexpected Keyboard layouts ([source](https://github.com/pashol/app-unexpected-keyboard-layout-editor)).

It lets you design keyboard layouts visually in your browser and export them as XML files ready to drop into `srcs/layouts/`. No manual XML editing required.

## Changes from upstream

Cherry-picked from upstream master:

**Spell checking / suggestions**
- Candidates view (suggestion strip above the keyboard)
- Word tracking as you type, autocomplete on space bar, undo on delete
- Dictionary-based spell checking with downloadable dictionaries
- Option to disable suggestions

**Bug fixes**
- Fix autocapitalisation flicker
- Fix missing swipe vibration and visual feedback
- Fix alternate keyboard layouts rendering behind navbar
- Fix very small label size on 11+ column layouts
- Fix insets being excluded from computed width
- Fix parsing of escaped characters in macros
- Fix clipboard manager crashes
- Fix crash with Monet themes on Android 9
- Fix label color when keys are pressed
- Fix space and delete not repeating
- Fix crash in capitalized word suggestion
- Fix no keyboard after switching from clipboard manager
- Avoid loops in modmaps

**Build / SDK**
- Gradle upgrade (Kotlin DSL)
- Target SDK 36
- 16 KB page size dictionary compatibility (Pixel 9+)

## Building

See [Contributing](CONTRIBUTING.md).

Initialize submodules first (required for dictionary support):
```bash
git submodule update --init
./gradlew assembleDebug
```
