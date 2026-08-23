# Experimental Prediction Build 1 Acceptance

Date: 2026-07-21

All Gradle commands below used:

```
PATH=/tmp/opencode/python-bin:$PATH
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ANDROID_HOME=/home/pascal/Android/Sdk
```

## Passed

- `./gradlew test` completed successfully in 1m 8s.
- `./gradlew assembleDebug` completed successfully in 11s.
- `./gradlew predictionBenchmark` completed successfully twice. Both runs emitted
  identical quality metrics and reported `"autocorrectionEnabled":false`:

  ```json
  {"legacy":{"top1Recall":0.75,"top3Recall":0.75,"mrr":0.75,"keystrokesSaved":1.0,"correctionPrecision":0.0,"reversionRate":0.3333333333333333,"p50Ms":0.030993,"p95Ms":0.404352,"p99Ms":0.404352},"experimental":{"top1Recall":0.25,"top3Recall":1.0,"mrr":0.625,"keystrokesSaved":0.4,"correctionPrecision":0.0,"reversionRate":0.3333333333333333,"p50Ms":0.07912,"p95Ms":0.162756,"p99Ms":0.162756}}
  ```

- `./gradlew testDebugUnitTest --tests juloo.keyboard2.prediction.LatinDecoderLifecycleTest --tests juloo.keyboard2.prediction.PredictionEngineControllerTest --tests juloo.keyboard2.prediction.PredictionEngineFactoryTest --tests juloo.keyboard2.KeyEventHandlerTest --tests juloo.keyboard2.suggestions.SuggestionsTest` completed successfully. These tests cover idempotent close, replacing engines, dictionary close counts, disabled-engine fallback, experimental space-autocomplete suppression, and legacy space-bar autocomplete.
- Static inspection found no logging calls in `srcs/juloo.keyboard2/prediction`. The general debug log calls do not include typed or candidate text.
- `KeyEventHandler.should_autocomplete_candidate` returns false for experimental candidates, and the focused tests confirm that experimental space presses leave typed text unchanged while legacy candidates still autocomplete when the experimental setting is off.

## Failed

- `./gradlew assembleRelease` failed during `:packageRelease` after native builds, R8, and resource optimization because signing configuration `release` has no `storeFile`. No release APK was packaged, so a release APK/native-size measurement is unavailable.

## Unrun

- API 21 and current-API smoke tests were not run. `/home/pascal/Android/Sdk/platform-tools/adb devices -l` returned only `List of devices attached`; no device or emulator was connected. The requested `PATH` also does not contain `adb`.
- The device smoke matrix is therefore unrun: legacy default, opt-in engine, toggle fallback, password suppression, no-personalized-learning behavior, English completion/correction/next-word, and Swiss German synthetic predictions.
- Airplane-mode prediction verification, runtime `logcat` inspection for typed/candidate text, and the on-device Java/native retained-memory and native-handle activation/prediction/destruction loop are unrun because no device is available.

## Debug Artifact Sizes

- Debug APK: `build/outputs/apk/debug/Unexpected-Keyboard-debug.apk`, 6,768,965 bytes.
- Unpacked debug `libcdict_java.so`: arm64-v8a 62,240 bytes; armeabi-v7a 54,156 bytes; x86 58,032 bytes; x86_64 58,672 bytes.
- Unpacked debug `libjni_latinime.so`: arm64-v8a 11,768,488 bytes; armeabi-v7a 10,947,300 bytes; x86 11,250,868 bytes; x86_64 11,436,328 bytes.
- APK-contained native-library sizes are 19,640/12,460/19,564/18,672 bytes for `libcdict_java.so` and 2,109,128/1,308,996/2,135,144/2,076,280 bytes for `libjni_latinime.so` in arm64-v8a/armeabi-v7a/x86/x86_64 order.
