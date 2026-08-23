# Experimental Prediction Build 1 Test Protocol

Use this protocol to complete the device-dependent Build 1 acceptance checks.

## Scope

Run the protocol on:

- One API 21 device or emulator.
- One current Android device or emulator.

Record the device model, Android API level, app build identifier, test date, and
whether the test used the debug or signed release APK.

## Prerequisites

1. Install the APK and enable Unexpected Keyboard as the active input method.
2. Use the English (`en`) and Swiss German (`gsw-CH`) prediction packs packaged
   by the Build 1 APK.
3. Start with a fresh app process for each major scenario.
4. Keep `adb logcat` running during the privacy and lifecycle scenarios:

   ```sh
   adb logcat -c
   adb logcat
   ```

5. For the offline scenario, enable airplane mode after the app and packs are
   installed. Do not connect the device to a network during prediction checks.

## Smoke Matrix

For every result, record pass/fail and a short observed result. Capture a
screenshot for a failure.

| Scenario | Steps | Expected result |
| --- | --- | --- |
| Legacy default | Leave `Experimental prediction engine` disabled. Type a known dictionary prefix and select a legacy suggestion. | Existing suggestions and selection behavior remain unchanged. |
| Legacy space autocomplete | With the experimental setting disabled, enter a legacy completion and press space. | Existing legacy `space_bar_auto_complete` behavior is unchanged. |
| Opt-in activation | Enable `Experimental prediction engine`, restart the input session, and type `hel`. | The candidate strip shows `hello`, `help`, or `held`; the typed text remains a candidate. |
| English Unicode | With the experimental setting enabled, type `café` and `naïve` prefixes. | Accented candidates retain their exact Unicode spelling. |
| English correction | Type a one-key-adjacent typo such as `helo`; select a correction. | A correction is offered as an explicit choice only. It is never inserted automatically. |
| English next word | Commit `hello`, then request suggestions with an empty composing word. | `world` or `there` is offered as a next-word candidate. |
| Swiss German variants | Switch to `gsw-CH`; type `n`, then use context `das` with an empty composing word. | Candidates retain dialect variants such as `nöd`, `nid`, and `ned`; `isch` is offered after `das`. |
| Toggle fallback | Enable experimental prediction, then disable it during a new input session. | The next session uses legacy suggestions without a crash or stale experimental candidates. |
| Missing/corrupt pack fallback | Temporarily remove or corrupt the copied locale pack only on a test device, then start a new session. | Suggestions immediately fall back to legacy behavior; the keyboard stays usable. Restore the pack afterward. |
| Password field | Focus a text password and number password field. | No experimental prediction or history learning occurs. |
| No personalized learning | Focus a field with `IME_FLAG_NO_PERSONALIZED_LEARNING`. Select and reject suggestions. | Static predictions may appear; history is not changed. |
| URI/email field | Focus URI and email fields. | Exact completion may appear; autocorrection and history learning are suppressed. |
| Explicit-only safety | Enable the experimental engine, type a typo, then press space without tapping a candidate. | The typed text is not replaced automatically. |

## Feedback And Reversion

1. Tap an experimental candidate, then immediately press backspace.
2. Verify the original typed text is restored.
3. Repeat after moving the cursor before backspace.
4. Verify cursor movement cancels the revert and backspace performs its normal
   edit instead.
5. Long-press a legacy personal candidate and verify its removal still works.
6. Verify the emoji slot remains selectable and is not treated as a prediction
   feedback event.

## Offline And Privacy Checks

1. Enable airplane mode.
2. Repeat English completion, correction, next-word, and Swiss German checks.
3. Verify the same local predictions work without connectivity.
4. Search the captured logcat output for text entered during the test and for
   displayed candidate text. Neither may appear in logs.
5. Record the exact search terms and whether any match was found. Do not attach
   logs containing typed private text to a public report.

## Lifecycle And Memory Check

1. Repeat at least 20 cycles of: activate input, enable experimental prediction,
   type `hel`, select a candidate, leave the input field, and return.
2. Monitor the app's Java and native memory with Android Studio Profiler or
   `adb shell dumpsys meminfo <package>` before, during, and after the loop.
3. Watch for crashes, repeated JNI errors, increasing retained native memory, or
   increasing open-handle counts.
4. Record the three memory snapshots and any observed errors.

## Completion Record

The acceptance report must include:

- Results for both API levels and every smoke-matrix row.
- Debug and, when signing is configured, release APK version and native sizes.
- Offline/logcat/privacy result.
- Lifecycle memory observations.
- Benchmark JSON from two `predictionBenchmark` runs.
- Any fallback, crash, Unicode, or candidate-order regression with reproduction
  steps.

Build 1 is not fully accepted until every required scenario passes or an
explicitly documented release decision accepts a known limitation.
