# Suggestion Strip Text Sizing

**Date:** 2026-03-25

## Problem

The suggestion strip shows large text that wraps onto a second line for long words (e.g. German compound words like "gleichberechtigtes"). This is caused by two issues:

1. Text size is computed to match keyboard key labels (scaled by `characterSize × labelTextSize × 1.15`), which is too large for a suggestion strip.
2. No `maxLines` constraint is set, so long words wrap and consume excessive vertical space.

## Goal

Candidate words should always fit on a single line, auto-shrinking when necessary, while still respecting the user's keyboard size preference for the maximum size.

## Approach

Use Android's built-in auto-size text feature (`TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration`) so each candidate independently shrinks its text to fit within its column, with the existing size formula as the ceiling.

## Changes

### `res/layout/keyboard.xml`

Add `android:maxLines="1"` to each of the three candidate TextViews (`candidates_left`, `candidates_middle`, `candidates_right`). This is required for auto-sizing to work — Android needs a fixed line limit to know the bounds to shrink into.

### `CandidatesView.java` — `set_height()`

Replace the direct `v.setTextSize(TypedValue.COMPLEX_UNIT_PX, text_size)` call with:

```java
TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
    v,
    (int)(height * 0.3f),  // min: ~30% of row height (unreadable floor)
    (int)text_size,         // max: existing formula (unchanged)
    1,                      // granularity: 1px
    TypedValue.COMPLEX_UNIT_PX
);
```

Add import: `androidx.core.widget.TextViewCompat`

## Constraints

- `androidx.core:core:1.16.0` is already a dependency — `TextViewCompat` is available on API 21+.
- No new dependencies required.
- The existing `text_size` formula (`height × characterSize × labelTextSize × 1.15f`) is preserved as the max, so the keyboard size preference still applies.
- Each candidate sizes independently — words of different lengths may render at different sizes, which is the accepted trade-off for approach A.
