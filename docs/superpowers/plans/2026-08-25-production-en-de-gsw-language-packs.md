# Production EN, DE, and GSW Language Packs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce separate, immutable, fully offline format-202 packs for English (`en`), German (`de` and `de-CH`), and Swiss German (`gsw`), with reproducible input processing and per-pack licensing records.

**Architecture:** Keep source acquisition and corpus transformation outside Gradle and outside the APK build. Versioned source snapshots are transformed into bounded UTF-8 unigram and bigram TSV files, then the existing pinned-AOSP builder compiles each into a format-202 dictionary. APK packaging copies only a reviewed pack registry and its binary assets; the runtime chooses an exact locale pack before a deliberately demoted fallback.

**Tech Stack:** Python 3 standard library, pinned AOSP LatinIME dicttool at `8081a1d8572f78488900438a6eaaec232b882bbf`, Android/Java, ECDC Translation Memory 2012 (EU/ECDC Reuse Licence), ArchiMob transcripts (CC BY-NC-SA 4.0).

---

## Source and licensing decisions

- `en`, `de`, and `de-CH`: ECDC Translation Memory (October 2012), under the EU/ECDC Reuse Licence implementing Commission Reuse Decision 2011/833/EU. The single official archive is compact, attribution/no-warranty/no-endorsement terms are retained, and its public-health bias is documented. Compile `de-CH` separately with its own locale-specific binary and manifest; it is not `gsw`.
- `gsw`: ArchiMob *text transcripts only*, CC BY-NC-SA 4.0. Do not include audio, annotations with other terms, or any corpus whose text rights are not explicit.
- Do not use FUTO/Lexiteria dictionaries, Common Crawl/OSCAR, SwissDial, or NOAH text.
- `gsw` must remain a distinct pack. Do not merge Standard German counts into its trie or silently label `de-CH` as Swiss German.
- Generated `gsw` assets must carry CC BY-NC-SA 4.0 attribution and ShareAlike terms. The release process must reject commercial-flavored variants of that asset.

### Task 1: Define reviewed production-pack metadata

**Files:**
- Create: `assets/latinime/packs/README.md`
- Create: `assets/latinime/packs/language_packs.json`
- Create: `assets/latinime/packs/ATTRIBUTION.en.md`
- Create: `assets/latinime/packs/ATTRIBUTION.de.md`
- Create: `assets/latinime/packs/ATTRIBUTION.gsw.md`
- Modify: `tools/prediction/test_build_language_pack.py`

- [ ] **Step 1: Write a failing registry-validation test**

```python
def test_production_registry_requires_asset_license_and_attribution(self):
    registry = {
        "format_version": 202,
        "packs": [{
            "locale": "gsw", "dictionary": "gsw.dict", "manifest": "gsw.json",
            "word_frequency_tsv": "sources/gsw.words.tsv", "ngram_tsv": "sources/gsw.ngrams.tsv",
            "provenance": "sources/gsw.provenance.json", "output_sha256": "0" * 64,
            "development_supported": False,
        }],
    }
    with self.assertRaisesRegex(ValueError, "attribution"):
        build_language_pack.validate_registry_entry(registry["packs"][0])
```

- [ ] **Step 2: Run the focused test and verify it fails because `validate_registry_entry` does not exist**

Run: `python3 -m unittest tools.prediction.test_build_language_pack.BuildLanguagePackTest.test_production_registry_requires_asset_license_and_attribution`

Expected: `ERROR` naming missing `validate_registry_entry`.

- [ ] **Step 3: Add minimal registry validation**

```python
def validate_registry_entry(pack):
    required = (
        "dictionary", "locale", "manifest", "ngram_tsv", "output_sha256",
        "provenance", "word_frequency_tsv", "asset_license", "attribution",
    )
    if any(not isinstance(pack.get(field), str) or not pack[field] for field in required):
        raise ValueError("language pack registry entries require an asset license and attribution")
```

Call it from `load_language_packs` before resolving paths. Preserve the fixture registry by adding `asset_license: "CC0-1.0"` and fixture attribution paths.

- [ ] **Step 4: Run focused tests and verify they pass**

Run: `python3 -m unittest tools.prediction.test_build_language_pack`

Expected: all tests pass.

- [ ] **Step 5: Add production metadata without binary assets**

Create a production registry with exact `en`, `de`, `de-CH`, and `gsw` locale entries, an explicit asset license, dictionary/manifest paths, and attribution paths. The README must state that source archives and intermediate TSVs are build inputs kept outside version control; checked-in binary packs and manifests are the reviewable release artifacts.

- [ ] **Step 6: Commit metadata validation**

```bash
git add tools/prediction/build_language_pack.py tools/prediction/test_build_language_pack.py \
  test/fixtures/latinime assets/latinime/packs
```

### Task 2: Transform ECDC TMX into EN and DE TSV data

**Files:**
- Create: `tools/prediction/import_ecdc_tm.py`
- Create: `tools/prediction/test_import_ecdc_tm.py`
- Create: `tools/prediction/sources/ecdc-tm-2012.md`

- [ ] **Step 1: Write failing tests for aggregation, filtering, and deterministic scores**

```python
def test_aggregate_two_gram_rows_sums_years_and_keeps_top_targets(self):
    rows = [
        "hello world\t2000\t4\t1\n", "hello world\t2001\t6\t2\n",
        "hello there\t2000\t3\t1\n", "bad-token! world\t2000\t99\t1\n",
    ]
    words, ngrams = importer.aggregate(rows, minimum_count=3, top_targets=1)
    self.assertEqual([("hello", 10), ("world", 10)], words)
    self.assertEqual([("hello", "world", 255)], ngrams)
```

- [ ] **Step 2: Run the focused test and verify it fails because the importer is absent**

Run: `python3 -m unittest tools.prediction.test_import_google_books_ngrams`

Expected: `ImportError` for `import_google_books_ngrams`.

- [ ] **Step 3: Implement the minimal streaming importer**

The command must require `--input`, `--locale`, `--words-output`, `--ngrams-output`, `--minimum-count`, and `--top-targets`; it must never download data. Parse only tab-separated rows shaped as `token token<TAB>year<TAB>match_count<TAB>volume_count`, aggregate counts across years, reject tokens containing whitespace or control characters, normalize NFC, and write sorted TSV. Map counts monotonically to `1..255` using the largest retained count as `255`.

```python
def score(count, maximum):
    return max(1, (count * 255 + maximum - 1) // maximum)
```

- [ ] **Step 4: Run focused tests and verify they pass**

Run: `python3 -m unittest tools.prediction.test_import_google_books_ngrams`

Expected: all tests pass.

- [ ] **Step 5: Document pinned source acquisition**

Document the exact Google dataset index, selected file-list URL, source SHA-256 recording process, CC BY 3.0 attribution text, expected input row format, and a command template. Do not add downloaded n-gram shards to Git.

- [ ] **Step 6: Commit the importer**

```bash
git add tools/prediction/import_google_books_ngrams.py \
  tools/prediction/test_import_google_books_ngrams.py \
  tools/prediction/sources/google-books-ngrams-v3.md
```

### Task 3: Transform licensed ArchiMob transcripts into GSW TSV data

**Files:**
- Create: `tools/prediction/import_archimob.py`
- Create: `tools/prediction/test_import_archimob.py`
- Create: `tools/prediction/sources/archimob.md`

- [ ] **Step 1: Write a failing test for variant-preserving tokenization**

```python
def test_tokenize_preserves_swiss_german_variants_and_builds_sentence_bigrams(self):
    self.assertEqual(
        ["Mär", "gönd", "nöd", "hei"],
        importer.tokenize("Mär gönd nöd hei."),
    )
    self.assertEqual(
        {("Mär", "gönd"): 1, ("gönd", "nöd"): 1, ("nöd", "hei"): 1},
        importer.count_bigrams(["Mär", "gönd", "nöd", "hei"]),
    )
```

- [ ] **Step 2: Run the focused test and verify it fails because the importer is absent**

Run: `python3 -m unittest tools.prediction.test_import_archimob`

Expected: `ImportError` for `import_archimob`.

- [ ] **Step 3: Implement minimal transcript-only processing**

Accept an explicit UTF-8 text export through `--input`; no downloader. Require a separately supplied `--source-sha256` and reject it if it differs from the input hash. Split sentence boundaries, preserve NFC, apostrophes, umlauts, casing, and observed fused words. Exclude XML markup, speaker IDs, timestamps, bracketed transcription annotations, URLs, and control characters. Emit deterministic sorted TSV and a JSON import report with accepted/rejected line counts.

- [ ] **Step 4: Run focused tests and verify they pass**

Run: `python3 -m unittest tools.prediction.test_import_archimob`

Expected: all tests pass.

- [ ] **Step 5: Document the CC BY-NC-SA release boundary**

State the transcript catalogue/version, source hash, required attribution, CC BY-NC-SA 4.0 URL, modification statement, and that `gsw` binaries may only be released under compatible non-commercial/share-alike terms.

- [ ] **Step 6: Commit the importer**

```bash
git add tools/prediction/import_archimob.py tools/prediction/test_import_archimob.py \
  tools/prediction/sources/archimob.md
```


- Modify: `tools/prediction/build_language_pack.py`
- Modify: `tools/prediction/copy_development_language_packs.py`
- Modify: `build.gradle.kts`
- Modify: `srcs/juloo.keyboard2/Keyboard2.java`
- Modify: `srcs/juloo.keyboard2/prediction/PredictionEngineController.java`
- Create: `assets/latinime/packs/{en,de,de-CH,gsw}.dict`
- Create: `assets/latinime/packs/{en,de,de-CH,gsw}.json`
- Create: `assets/latinime/packs/sources/{en,de,gsw}.{words,ngrams,provenance}.tsv`
- Test: `tools/prediction/test_release_packaging.py`

- [ ] **Step 1: Write failing packaging tests**

```python
def test_release_packaging_rejects_a_gsw_pack_without_nc_sa_attribution(self):
    with self.assertRaisesRegex(ValueError, "CC BY-NC-SA-4.0"):
        packaging.validate_pack_notice({"locale": "gsw", "asset_license": "CC BY 3.0"})

def test_runtime_registry_prefers_exact_gsw_over_german_fallback(self):
    self.assertEqual("gsw.dict", registry.dictionary_for(Locale.forLanguageTag("gsw-CH")))
```

- [ ] **Step 2: Run the focused tests and verify they fail for missing production-pack support**

Run: `python3 -m unittest tools.prediction.test_release_packaging`

Expected: failures naming the missing pack-notice validator and runtime selection behavior.

- [ ] **Step 3: Implement immutable production asset packaging**

Make Gradle copy production dictionaries, manifests, attributions, and a registry from `assets/latinime/packs` to `build/generated-assets/latinime/packs`. Replace the English-fixture-only copy naming scheme with registry-driven names. Keep the tiny fixture assets available only to unit/instrumentation tests. At runtime, copy a selected APK asset atomically to app storage, verify its SHA-256 against the manifest before opening it, and select exact locale before `de-CH -> de`; never use `de` as an automatic fallback for `gsw`.

- [ ] **Step 4: Generate only from approved source snapshots**

For each corpus, run its importer to produce source TSVs and provenance JSON with URL/version/source SHA-256/license. Build with the existing command under `SOURCE_DATE_EPOCH=0`. Inspect manifests and verify their output hashes against the registry. Do not commit a pack until source acquisition, source hash, and attribution have been reviewed.

- [ ] **Step 5: Run focused tests and verify they pass**

Run: `python3 -m unittest tools.prediction.test_release_packaging tools.prediction.test_build_language_pack`

Expected: all tests pass.

- [ ] **Step 6: Commit pack packaging**

```bash
git add build.gradle.kts srcs/juloo.keyboard2 tools/prediction assets/latinime/packs
```

### Task 5: End-to-end validation and attribution review

**Files:**
- Modify: `androidTest/juloo.keyboard2/prediction/LatinimeDictionaryInstrumentationTest.java`
- Modify: `tools/prediction/README.md`
- Modify: `docs/superpowers/specs/2026-08-23-next-word-prediction-design.md`

- [ ] **Step 1: Write failing locale-selection integration tests**

```java
@Test public void selectsSwissGermanPackWithoutGermanizationFallback() {
  assertEquals("gsw", registry.packFor(Locale.forLanguageTag("gsw-CH")).locale());
}

@Test public void selectsGermanFallbackForSwissStandardGerman() {
  assertEquals("de-CH", registry.packFor(Locale.forLanguageTag("de-CH")).locale());
}
```

- [ ] **Step 2: Run the focused instrumentation test and verify it fails before the selection implementation**

Run: `./gradlew connectedDebugAndroidTest --tests '*LatinimeDictionaryInstrumentationTest*'`

Expected: locale-pack selection assertion fails.

- [ ] **Step 3: Implement only the selection behavior needed by the tests**

Route locale resolution through the signed registry asset, with exact language/region first, then the documented `de-CH -> de` fallback. Return no static fallback for a missing `gsw` pack.

- [ ] **Step 4: Run all automated verification**

Run: `python3 -m unittest discover -s tools/prediction -p 'test_*.py'`

Expected: all Python tests pass.

Run: `./gradlew test connectedDebugAndroidTest assembleDebug`

Expected: all unit and connected tests pass; debug APK builds.

- [ ] **Step 5: Review assets before release**

Verify each manifest’s source and output hashes; inspect APK entries for all four packs and attribution documents; confirm no `gsw` asset is shipped without the CC BY-NC-SA 4.0 notice. Update the design document to identify production packs and retain fixture-only behavior in test documentation.

- [ ] **Step 6: Commit validation and documentation**

```bash
git add androidTest tools/prediction/README.md docs/superpowers/specs
```
