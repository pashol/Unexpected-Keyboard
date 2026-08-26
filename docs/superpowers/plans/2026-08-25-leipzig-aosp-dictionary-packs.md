# Leipzig/AOSP-Dictionary EN, DE, DE-CH Language Packs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the public-health-biased ECDC-based `en`, `de`, and `de-CH` production prediction packs with general-purpose packs built from Helium314's `aosp-dictionaries` Leipzig-derived wordlists (CC BY 4.0, with bigrams), where `de-CH` additionally overlays OpenBoard Swiss Helvetismen under Swiss `ß→ss` orthography.

**Architecture:** Follow the established promotion pipeline: a new importer turns AOSP combined-format wordlists into bounded unigram/bigram TSVs plus an import report; the pinned-AOSP `build_language_pack.py` compiles them deterministically (format 202, JDK 17.0.19, `SOURCE_DATE_EPOCH=0`); artifacts are attested, registered in `assets/latinime/packs/language_packs.json`, and verified by the committed chain validator. Because `validate_provenance` currently permits only one external corpus per ready pack, it gains a multi-shard mode matched against `acquisition_lock.shards`. No Android code changes: `ProductionPredictionPack` consumes the unchanged registry schema.

**Tech Stack:** Python 3 stdlib, pinned AOSP LatinIME dicttool at `8081a1d8572f78488900438a6eaaec232b882bbf` (`/tmp/opencode/LatinIME`, clean), JDK 17.0.19 at `/usr/lib/jvm/java-17-openjdk-amd64`, Helium314/aosp-dictionaries `main_en_US.combined` + `main_de.combined` (Leipzig corpora, CC BY 4.0), OpenBoard v1.4.5 `de_wordlist.combined.gz` (Apache-2.0 + CC BY-SA 4.0 Helvetismen).

**Working directory:** all commands run from `/home/pascal/Code/Unexpected-Keyboard-upstream-port/.worktrees/next-word-prediction` unless stated otherwise. Promotion scratch data lives in `/tmp/opencode/packs-promotion/`.

---

## File Structure

- Modify `tools/prediction/build_language_pack.py` — generalize `validate_provenance` for multi-shard acquisition locks (only behavioral change to the trusted builder)
- Modify `tools/prediction/test_build_language_pack.py` — tests for the multi-shard mode
- Create `tools/prediction/import_aosp_combined.py` — combined-format importer (parser, character mapping, overlay merge, selection, generation publishing)
- Create `tools/prediction/test_import_aosp_combined.py` — its tests
- Create `tools/prediction/sources/aosp-dictionaries.md` — pinned acquisition + license provenance doc
- Modify `tools/prediction/README.md` — document the new importer and source
- Modify `.gitignore` — never commit staged TSVs/provenance/combined files under the pack directory
- Modify `assets/latinime/packs/language_packs.json` — replace `en`, `de`, `de-CH` entries (new locks, hashes, licenses, attestations)
- Replace `assets/latinime/packs/{en,de,de-CH}.dict` and `{en,de,de-CH}.json`
- Replace `assets/latinime/packs/{en,de,de-CH}.attestation.json`
- Rewrite `assets/latinime/packs/ATTRIBUTION.en.md`, `ATTRIBUTION.de.md`; create `ATTRIBUTION.de-CH.md`
- Untouched: `gsw.*`, `ATTRIBUTION.gsw.md`, all Android sources

## Design decisions locked in

1. **Frequencies:** upstream combined `f=` values are already rank-tuned scores. They are inserted raw; `_publish_generation` performs one linear stretch per table (`score(count, max)`), preserving order deterministically. No double scoring.
2. **Selection knobs:** `--minimum-count` thresholds bigram candidate frequency *before* capping each context to `--top-targets` targets sorted by `(-f, target)`. All words are kept (vocabulary completeness beats size for typing); bigrams drive size.
3. **Character mapping:** `--map old:new` (repeatable) applied to both base and overlay inputs before merging; collisions resolve by max frequency. `de-CH` uses `--map ß:ss`.
4. **Hybrid de-CH:** base `main_de.combined` mapped `ß→ss`, overlaid union-max with the entire OpenBoard `de_wordlist.combined.gz` (already `ß`-free). Provenance declares two external corpora matched to two lock shards.
5. **Multi-shard provenance:** single-shard locks keep today's semantics verbatim (corpus hash == canonical lock hash). Multi-shard locks require one corpus per shard, each declaring `"shard": "<name>"`, with corpus `source_sha256` equal to that shard's archive SHA-256.
6. **Bootstrap:** a first-ever ready pack cannot pass `load_language_packs` (it hashes not-yet-existing outputs). Builds therefore run against a *staging registry copy* in `/tmp/opencode/packs-promotion/staging/` whose placeholder artifacts are internally consistent (empty dicts, matching placeholder manifests/attestations). Only verified outputs are copied into `assets/latinime/packs/`.
7. `en` uses the US wordlist under locale key `en` (matches the shipped pack slot and runtime language-tag fallback).

---

### Task 1: Pin and acquire external sources

**Files:**
- Modify: `.gitignore`
- Create (scratch, not committed): `/tmp/opencode/packs-promotion/hashes.json`

- [ ] **Step 1: Ignore staged promotion inputs**

Append to `.gitignore` (create the entry under the existing `/release` block):

```gitignore
# Promotion-time TSVs, provenance, combined sources, and receipts stay outside Git
/assets/latinime/packs/sources/
/assets/latinime/packs/*.combined
```

- [ ] **Step 2: Download the three pinned archives**

```bash
mkdir -p /tmp/opencode/packs-promotion/{downloads,staging,gen}
cd /tmp/opencode/packs-promotion/downloads
curl -fsSLo main_en_US.combined "https://codeberg.org/Helium314/aosp-dictionaries/raw/branch/main/wordlists_experimental/main_en_US.combined"
curl -fsSLo main_de.combined "https://codeberg.org/Helium314/aosp-dictionaries/raw/branch/main/wordlists_experimental/main_de.combined"
curl -fsSLo openboard_de_CH_wordlist.combined.gz "https://github.com/openboard-team/openboard/raw/v1.4.5/dictionaries/de_wordlist.combined.gz"
```

- [ ] **Step 3: Record immutable identifiers**

```bash
CODEBERG_COMMIT=$(curl -fsS "https://codeberg.org/api/v1/repos/Helium314/aosp-dictionaries/commits?page=1&limit=1" | python3 -c "import json,sys;print(json.load(sys.stdin)[0]['sha'])")
python3 - "$CODEBERG_COMMIT" <<'EOF'
import hashlib, json, pathlib, sys
def h(name):
    return hashlib.sha256(pathlib.Path(name).read_bytes()).hexdigest()
downloads = pathlib.Path("/tmp/opencode/packs-promotion/downloads")
hashes = {
    "codeberg_commit": sys.argv[1],
    "openboard_version": "v1.4.5",
    "main_en_US.combined": h(downloads / "main_en_US.combined"),
    "main_de.combined": h(downloads / "main_de.combined"),
    "openboard_de_CH_wordlist.combined.gz": h(downloads / "openboard_de_CH_wordlist.combined.gz"),
}
pathlib.Path("/tmp/opencode/packs-promotion/hashes.json").write_text(json.dumps(hashes, indent=2) + "\n")
print(json.dumps(hashes, indent=2))
EOF
```

Expected: JSON with three 64-hex hashes and the Codeberg tip commit (`69afafc3887d…` or newer).

- [ ] **Step 4: Sanity-inspect the inputs**

```bash
grep -m2 -c "^bigram=" /tmp/opencode/packs-promotion/downloads/main_en_US.combined || true
zcat /tmp/opencode/packs-promotion/downloads/openboard_de_CH_wordlist.combined.gz | head -3
```

Expected: `main_en_US.combined` shows indented ` bigram=` lines (count via `grep -c " bigram="`); OpenBoard file starts with a `dictionary=…` header followed by ` word=` lines and no `bigram=` lines.

- [ ] **Step 5: Verify the toolchain prerequisites**

```bash
/usr/lib/jvm/java-17-openjdk-amd64/bin/javac -version   # javac 17.0.19
git -C /tmp/opencode/LatinIME rev-parse HEAD            # 8081a1d8572f78488900438a6eaaec232b882bbf
git -C /tmp/opencode/LatinIME status --porcelain        # empty output
```

If the checkout is missing or dirty, re-acquire it at the pinned commit before continuing; the builder hard-fails otherwise.

- [ ] **Step 6: Commit**

```bash
git add .gitignore && git commit -m "chore: ignore staged language-pack promotion inputs"
```

---

### Task 2: Multi-shard provenance validation (TDD)

**Files:**
- Modify: `tools/prediction/build_language_pack.py:287-345` (`validate_provenance`) and its two call sites (`main()` ~line 662, `validate_ready_pack_assets` ~line 391)
- Test: `tools/prediction/test_build_language_pack.py`

- [ ] **Step 1: Write failing tests**

Append to `tools/prediction/test_build_language_pack.py`:

```python
class MultiShardProvenanceTest(unittest.TestCase):
    LOCK = {
        "state": "locked",
        "url": "https://example.test/src",
        "version": "v1",
        "shards": [
            {"name": "a.combined", "sha256": "a" * 64},
            {"name": "b.combined", "sha256": "b" * 64},
        ],
    }

    @staticmethod
    def _corpus(name, sha, shard=None):
        source = {
            "type": "external_corpus",
            "license": "Test License",
            "source_sha256": sha,
            "url": "https://example.test/" + name,
            "version": "v1",
        }
        if shard is not None:
            source["shard"] = shard
        return source

    def test_multi_shard_accepts_one_corpus_per_shard(self):
        provenance = {
            "s1": self._corpus("a.combined", "a" * 64, "a.combined"),
            "s2": self._corpus("b.combined", "b" * 64, "b.combined"),
        }
        self.assertEqual(
            provenance,
            build_language_pack.validate_provenance(provenance, acquisition_lock=self.LOCK),
        )

    def test_multi_shard_rejects_duplicate_shard_name(self):
        provenance = {
            "s1": self._corpus("a.combined", "a" * 64, "a.combined"),
            "s2": self._corpus("b.combined", "b" * 64, "a.combined"),
        }
        with self.assertRaisesRegex(ValueError, "unique acquisition shard"):
            build_language_pack.validate_provenance(provenance, acquisition_lock=self.LOCK)

    def test_multi_shard_rejects_unknown_shard_name(self):
        provenance = {
            "s1": self._corpus("a.combined", "a" * 64, "missing.combined"),
            "s2": self._corpus("b.combined", "b" * 64, "b.combined"),
        }
        with self.assertRaisesRegex(ValueError, "unique acquisition shard"):
            build_language_pack.validate_provenance(provenance, acquisition_lock=self.LOCK)

    def test_multi_shard_rejects_incomplete_coverage(self):
        provenance = {"s1": self._corpus("a.combined", "a" * 64, "a.combined")}
        with self.assertRaisesRegex(ValueError, "cover every acquisition shard"):
            build_language_pack.validate_provenance(provenance, acquisition_lock=self.LOCK)

    def test_multi_shard_rejects_shard_hash_mismatch(self):
        provenance = {
            "s1": self._corpus("a.combined", "c" * 64, "a.combined"),
            "s2": self._corpus("b.combined", "b" * 64, "b.combined"),
        }
        with self.assertRaisesRegex(ValueError, "must match its acquisition shard"):
            build_language_pack.validate_provenance(provenance, acquisition_lock=self.LOCK)

    def test_single_shard_keeps_canonical_lock_hash_semantics(self):
        single = {
            "state": "locked",
            "url": "https://example.test/src",
            "version": "v1",
            "shards": [{"name": "one.zip", "sha256": "1" * 64}],
        }
        provenance = {
            "only": self._corpus(
                "one.zip",
                build_language_pack.acquisition_lock_sha256(single),
            )
        }
        self.assertEqual(
            provenance,
            build_language_pack.validate_provenance(provenance, acquisition_lock=single),
        )

    def test_single_shard_still_rejects_foreign_hash(self):
        single = {
            "state": "locked",
            "url": "https://example.test/src",
            "version": "v1",
            "shards": [{"name": "one.zip", "sha256": "1" * 64}],
        }
        provenance = {"only": self._corpus("one.zip", "2" * 64)}
        with self.assertRaisesRegex(ValueError, "must match the acquisition lock"):
            build_language_pack.validate_provenance(provenance, acquisition_lock=single)
```

If the test file imports the builder differently than `from tools.prediction import build_language_pack`, adapt the reference accordingly.

- [ ] **Step 2: Run tests, verify red**

Run: `python3 -m unittest tools.prediction.test_build_language_pack.MultiShardProvenanceTest -v`
Expected: errors — `validate_provenance() got an unexpected keyword argument 'acquisition_lock'`.

- [ ] **Step 3: Implement multi-shard mode**

In `build_language_pack.py`, change the signature (line 287) to:

```python
def validate_provenance(
        provenance, provenance_directory=None, declared_inputs=None, acquisition_lock=None):
```

Replace the entire `if external_source_sha256 is not None:` block (lines 336-344) with:

```python
    if acquisition_lock is not None:
        shards = acquisition_lock["shards"]
        if len(shards) == 1:
            if len(external_corpora) != 1:
                raise ValueError("ready pack provenance must contain exactly one external corpus")
            corpus = external_corpora[0]
            if corpus["source_sha256"] != acquisition_lock_sha256(acquisition_lock):
                raise ValueError("external corpus hash must match the acquisition lock")
            if corpus["url"] != acquisition_lock["url"]:
                raise ValueError("external corpus source URL must match the acquisition lock")
            if corpus["version"] != acquisition_lock["version"]:
                raise ValueError("external corpus source version must match the acquisition lock")
            return provenance
        matched = {}
        for corpus in external_corpora:
            shard_name = corpus.get("shard")
            if not isinstance(shard_name, str) or shard_name in matched or not any(
                    shard["name"] == shard_name for shard in shards):
                raise ValueError(
                    "multi-shard provenance corpora must declare unique acquisition shard names"
                )
            matched[shard_name] = corpus
        if len(matched) != len(shards):
            raise ValueError("multi-shard provenance must cover every acquisition shard")
        for shard in shards:
            if matched[shard["name"]]["source_sha256"] != shard["sha256"]:
                raise ValueError("provenance corpus hash must match its acquisition shard")
    return provenance
```

Update the `validate_ready_pack_assets` call site (~line 391) to:

```python
    validate_provenance(
        {name: source for name, source in sources.items() if name not in GENERATED_SOURCE_NAMES},
        acquisition_lock=pack["acquisition_lock"],
    )
```

Update the `main()` call site (~line 662) to:

```python
        lock = pack.get("acquisition_lock")
        provenance = validate_provenance(
                json.loads(args.provenance.read_text(encoding="utf-8")),
                args.provenance.parent,
                {
                    args.word_frequency_tsv: word_frequency_tsv,
                    args.ngram_tsv: ngram_tsv,
                },
                acquisition_lock=lock if isinstance(lock, dict) else None,
        )
```

Then search for stale keyword usages and fix them:

```bash
grep -rn "external_source_sha256\|external_source_url\|external_source_version" tools/prediction/
```

Every remaining hit outside the removed parameters (tests included) must be migrated to `acquisition_lock=...`.

- [ ] **Step 4: Run tests, verify green**

Run: `python3 -m unittest tools.prediction.test_build_language_pack -v`
Expected: all pass, including pre-existing provenance tests.

- [ ] **Step 5: Commit**

```bash
git add tools/prediction/build_language_pack.py tools/prediction/test_build_language_pack.py
git commit -m "feat: support multi-shard acquisition locks in provenance validation"
```

---

### Task 3: Combined-format parser (TDD)

**Files:**
- Create: `tools/prediction/import_aosp_combined.py`
- Test: `tools/prediction/test_import_aosp_combined.py`

- [ ] **Step 1: Write failing parser tests**

Create `tools/prediction/test_import_aosp_combined.py`:

```python
import unittest

from tools.prediction import import_aosp_combined as importer


PARSE_INPUT = [
    "dictionary=main:de,locale=de_DE,description=x,date=1704207611,version=18",
    " word=Straße,f=222",
    "  bigram=Fuß,f=3",
    "  bigram=Haus,f=1",
    " word=Haus,f=200",
    " shortcut=foo,f=2",
    "",
    " word=Straße,f=210",
]


class ParseCombinedTest(unittest.TestCase):
    def test_parses_words_bigrams_and_repeats_by_maximum(self):
        words, bigrams, skipped = importer.parse_combined(PARSE_INPUT)
        self.assertEqual({"Straße": 222, "Haus": 200}, words)
        self.assertEqual({("Straße", "Fuß"): 3, ("Straße", "Haus"): 1}, bigrams)
        self.assertEqual(2, skipped)  # shortcut line + blank line

    def test_rejects_missing_header(self):
        with self.assertRaisesRegex(ValueError, "header"):
            importer.parse_combined([" word=x,f=1"])

    def test_rejects_malformed_frequency(self):
        with self.assertRaisesRegex(ValueError, "frequency"):
            importer.parse_combined(["dictionary=main:x", " word=x,f=high"])

    def test_drops_non_positive_frequencies(self):
        words, bigrams, dropped = importer.parse_combined(
            ["dictionary=main:x", " word=x,f=0", " word=y,f=2", "  bigram=z,f=-1"]
        )
        self.assertEqual({"y": 2}, words)
        self.assertEqual({}, bigrams)
        self.assertEqual(2, dropped)


class ApplyMapsTest(unittest.TestCase):
    def test_maps_characters_and_merges_collisions_by_maximum(self):
        words = {"Straße": 222, "Strasse": 100, "Haus": 50}
        bigrams = {("Straße", "Fuß"): 3, ("Strasse", "Fuss"): 2}
        mapped_words, mapped_bigrams, replacements = importer.apply_word_maps(
            words, bigrams, [("ß", "ss")]
        )
        self.assertEqual({"Strasse": 222, "Haus": 50}, mapped_words)
        self.assertEqual({("Strasse", "Fuss"): 3}, mapped_bigrams)
        self.assertEqual(1, replacements)


class MergeOverlayTest(unittest.TestCase):
    def test_union_maximum_merge_counts_contributions(self):
        words, bigrams, report = importer.merge_overlay(
            {"Strasse": 222},
            {("Strasse", "Haus"): 3},
            {"Strasse": 10, "Velo": 90, "Billett": 80},
            {},
        )
        self.assertEqual({"Strasse": 222, "Velo": 90, "Billett": 80}, words)
        self.assertEqual({("Strasse", "Haus"): 3}, bigrams)
        self.assertEqual(1, report["overlay_added_words"])
        self.assertEqual(1, report["overlay_merged_words"])
        self.assertEqual(0, report["overlay_added_bigrams"])


class SelectNgramsTest(unittest.TestCase):
    def test_filters_endpoints_threshold_and_caps_targets(self):
        selected, capped, below = importer.select_ngrams(
            {"a": 9, "b": 8, "c": 7, "x": 5},
            {("a", "b"): 4, ("a", "c"): 3, ("a", "x"): 2, ("b", "a"): 5, ("x", "a"): 1},
            minimum_count=2,
            top_targets=2,
        )
        self.assertEqual(
            {("a", "b"): 4, ("a", "c"): 3, ("b", "a"): 5},
            selected,
        )
        self.assertEqual(1, capped)  # ("a","x") dropped by the target cap
        self.assertEqual(1, below)   # ("x","a") below minimum_count
```

- [ ] **Step 2: Run tests, verify red**

Run: `python3 -m unittest tools.prediction.test_import_aosp_combined -v`
Expected: `ModuleNotFoundError: tools.prediction.import_aosp_combined`.

- [ ] **Step 3: Implement the parser core**

Create `tools/prediction/import_aosp_combined.py`:

```python
"""Transform AOSP combined-format word lists into dictionary TSV generations."""

import argparse
import gzip
import hashlib
import json
import pathlib
import sqlite3

from tools.prediction import import_archimob
from tools.prediction import import_google_books_ngrams


MAX_INPUT_BYTES = 64 * 1024 * 1024
MAX_OVERLAY_BYTES = 8 * 1024 * 1024
HEADER_PREFIX = "dictionary="
WORD_PREFIX = "word="
BIGRAM_PREFIX = "bigram="


def parse_combined(lines):
    """Parse header, ``word=`` and ``bigram=`` entries; return words, bigrams, stats."""
    header_seen = False
    words = {}
    bigrams = {}
    skipped = 0
    dropped = 0
    context = None
    for raw_line in lines:
        line = raw_line.rstrip("\r\n")
        if not header_seen:
            if line.startswith(HEADER_PREFIX):
                header_seen = True
            elif line.strip():
                raise ValueError("combined input lacks a dictionary header")
            continue
        stripped = line.strip()
        if not stripped:
            skipped += 1
            continue
        if stripped.startswith(WORD_PREFIX):
            word, frequency = _parse_entry(stripped, WORD_PREFIX)
            if frequency <= 0:
                dropped += 1
                continue
            words[word] = max(words.get(word, 0), frequency)
            context = word
        elif stripped.startswith(BIGRAM_PREFIX):
            if context is None:
                raise ValueError("bigram entry precedes its context word")
            target, frequency = _parse_entry(stripped, BIGRAM_PREFIX)
            if frequency <= 0:
                dropped += 1
                continue
            bigrams[(context, target)] = max(bigrams.get((context, target), 0), frequency)
        else:
            skipped += 1
    if not header_seen:
        raise ValueError("combined input lacks a dictionary header")
    return words, bigrams, {"skipped_lines": skipped, "dropped_entries": dropped}


def _parse_entry(entry, prefix):
    body = entry[len(prefix):]
    name, separator, frequency_text = body.rpartition(",f=")
    if not separator or not name:
        raise ValueError("malformed combined entry: " + prefix)
    try:
        return name, int(frequency_text)
    except ValueError as error:
        raise ValueError("malformed combined frequency: " + body) from error


def apply_word_maps(words, bigrams, maps):
    """Apply literal old:new replacements; collisions keep the maximum frequency."""
    def map_word(word):
        for old, new in maps:
            word = word.replace(old, new)
        return word

    mapped_words = {}
    replacements = 0
    for word, frequency in words.items():
        mapped = map_word(word)
        if mapped != word:
            replacements += 1
        mapped_words[mapped] = max(mapped_words.get(mapped, 0), frequency)
    mapped_bigrams = {}
    for (context, target), frequency in bigrams.items():
        edge = (map_word(context), map_word(target))
        mapped_bigrams[edge] = max(mapped_bigrams.get(edge, 0), frequency)
    return mapped_words, mapped_bigrams, replacements


def merge_overlay(words, bigrams, overlay_words, overlay_bigrams):
    """Union-max merge of overlay vocabulary and bigrams; returns merged data and counts."""
    merged = dict(words)
    added_words = 0
    merged_words = 0
    for word, frequency in overlay_words.items():
        if word in merged:
            if frequency > merged[word]:
                merged[word] = frequency
            merged_words += 1
        else:
            merged[word] = frequency
            added_words += 1
    merged_bigrams = dict(bigrams)
    added_bigrams = 0
    merged_bigram_edges = 0
    for edge, frequency in overlay_bigrams.items():
        if edge in merged_bigrams:
            if frequency > merged_bigrams[edge]:
                merged_bigrams[edge] = frequency
            merged_bigram_edges += 1
        else:
            merged_bigrams[edge] = frequency
            added_bigrams += 1
    report = {
        "overlay_added_words": added_words,
        "overlay_merged_words": merged_words,
        "overlay_added_bigrams": added_bigrams,
        "overlay_merged_bigrams": merged_bigram_edges,
    }
    return merged, merged_bigrams, report


def select_ngrams(words, bigrams, minimum_count, top_targets):
    """Keep bigrams whose endpoints are known, above the threshold, best-per-context."""
    if minimum_count <= 0:
        raise ValueError("minimum_count must be positive")
    if top_targets <= 0:
        raise ValueError("top_targets must be positive")
    candidates = {}
    below = 0
    for (context, target), frequency in bigrams.items():
        if context not in words or target not in words:
            continue
        if frequency < minimum_count:
            below += 1
            continue
        candidates.setdefault(context, []).append((target, frequency))
    selected = {}
    capped = 0
    for context in sorted(candidates):
        ranked = sorted(candidates[context], key=lambda item: (-item[1], item[0]))
        for target, frequency in ranked[:top_targets]:
            selected[(context, target)] = frequency
        capped += max(0, len(ranked) - top_targets)
    return selected, capped, below
```

- [ ] **Step 4: Run tests, verify green**

Run: `python3 -m unittest tools.prediction.test_import_aosp_combined -v`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add tools/prediction/import_aosp_combined.py tools/prediction/test_import_aosp_combined.py
git commit -m "feat: parse AOSP combined wordlists with mapping and overlay primitives"
```

---

### Task 4: Importer CLI and generation publishing (TDD)

**Files:**
- Modify: `tools/prediction/import_aosp_combined.py`
- Test: `tools/prediction/test_import_aosp_combined.py`

- [ ] **Step 1: Write failing end-to-end tests**

Append to `tools/prediction/test_import_aosp_combined.py`:

```python
import json
import tempfile

from tools.prediction import import_google_books_ngrams


BASE = "\n".join([
    "dictionary=main:de,locale=de_DE,description=x,version=18",
    " word=Straße,f=222",
    "  bigram=Fuß,f=3",
    " word=Haus,f=200",
    "  bigram=Straße,f=2",
]) + "\n"
OVERLAY = "\n".join([
    "dictionary=main:de_CH,vendor=openboard",
    " word=Velo,f=90",
    " word=Haus,f=250",
]) + "\n"


class CliTest(unittest.TestCase):
    def _run(self, arguments):
        importer.main(arguments)

    def test_publishes_scored_generations_with_report_and_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            base = root / "base.combined"
            base.write_text(BASE, encoding="utf-8")
            overlay = root / "overlay.combined"
            overlay.write_text(OVERLAY, encoding="utf-8")
            words_out = root / "de.words.tsv"
            ngrams_out = root / "de.ngrams.tsv"
            report_out = root / "de.import-report.json"
            self._run([
                "--input", str(base), "--input-sha256", _sha(base),
                "--overlay", str(overlay), "--overlay-sha256", _sha(overlay),
                "--map", "ß:ss",
                "--words-output", str(words_out), "--ngrams-output", str(ngrams_out),
                "--report-output", str(report_out),
                "--minimum-count", "1", "--top-targets", "8",
            ])
            pointer = json.loads(
                import_google_books_ngrams.current_manifest_path(words_out, ngrams_out)
                .read_text(encoding="utf-8"))
            self.assertEqual(1, pointer["format_version"])
            words = dict(
                line.split("\t") for line in
                words_out.read_text(encoding="utf-8").splitlines())
            self.assertEqual({"Strasse", "Haus", "Velo"}, set(words))
            self.assertEqual("255", words["Strasse"])  # highest f stretches to 255
            report = json.loads(report_out.read_text(encoding="utf-8"))
            self.assertEqual(1, report["overlay_added_words"])
            self.assertEqual(1, report["mapped_words"])
            self.assertEqual(2, report["accepted_bigrams"])

    def test_rejects_input_hash_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            base = root / "base.combined"
            base.write_text(BASE, encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                self._run([
                    "--input", str(base), "--input-sha256", "0" * 64,
                    "--words-output", str(root / "w.tsv"),
                    "--ngrams-output", str(root / "n.tsv"),
                    "--report-output", str(root / "r.json"),
                    "--minimum-count", "1", "--top-targets", "8",
                ])


def _sha(path):
    import hashlib
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()
```

Add `import pathlib` to the test module imports.

- [ ] **Step 2: Run tests, verify red**

Run: `python3 -m unittest tools.prediction.test_import_aosp_combined.CliTest -v`
Expected: `AttributeError: ... has no attribute 'main'`.

- [ ] **Step 3: Implement the CLI**

Append to `tools/prediction/import_aosp_combined.py`:

```python
def _sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _validate_hex(value, description):
    if len(value) != 64:
        raise ValueError(description + " must be a SHA-256 hash")
    try:
        int(value, 16)
    except ValueError as error:
        raise ValueError(description + " must be a SHA-256 hash") from error


def _read_source(path, limit, description):
    data = path.read_bytes()
    if len(data) > limit:
        raise ValueError(description + " exceeds size limit")
    if data[:2] == b"\x1f\x8b":
        with gzip.open(path, "rt", encoding="utf-8") as text_source:
            return text_source.readlines(), data
    return (
        path.open("r", encoding="utf-8").read().splitlines(keepends=True),
        data,
    )


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=pathlib.Path)
    parser.add_argument("--input-sha256", required=True)
    parser.add_argument("--overlay", type=pathlib.Path)
    parser.add_argument("--overlay-sha256")
    parser.add_argument("--map", dest="maps", action="append", default=[],
                        metavar="OLD:NEW",
                        help="literal character replacement, repeatable")
    parser.add_argument("--words-output", required=True, type=pathlib.Path)
    parser.add_argument("--ngrams-output", required=True, type=pathlib.Path)
    parser.add_argument("--report-output", required=True, type=pathlib.Path)
    parser.add_argument("--minimum-count", required=True,
                        type=import_archimob._positive_integer)
    parser.add_argument("--top-targets", required=True,
                        type=import_archimob._positive_integer)
    arguments = parser.parse_args(argv)

    maps = []
    for specification in arguments.maps:
        old, separator, new = specification.partition(":")
        if not separator or not old:
            parser.error("--map must take the form OLD:NEW")
        maps.append((old, new))

    _validate_hex(arguments.input_sha256, "--input-sha256")
    if arguments.overlay is not None:
        if arguments.overlay_sha256 is None:
            parser.error("--overlay requires --overlay-sha256")
        _validate_hex(arguments.overlay_sha256, "--overlay-sha256")

    import_google_books_ngrams.validate_paths(
        arguments.input, arguments.words_output, arguments.ngrams_output
    )
    import_archimob._validate_report_output_path(
        arguments.report_output, arguments.words_output, arguments.ngrams_output
    )

    actual_input_hash = _sha256_file(arguments.input)
    if actual_input_hash != arguments.input_sha256:
        raise ValueError("source SHA-256 does not match --input-sha256")
    lines, _ = _read_source(arguments.input, MAX_INPUT_BYTES, "combined input")
    words, bigrams, stats = parse_combined(lines)
    report = {
        "input_sha256": actual_input_hash,
        "accepted_words": len(words),
        "accepted_bigrams_before_selection": len(bigrams),
        "minimum_count": arguments.minimum_count,
        "top_targets": arguments.top_targets,
        "maps": [old + ":" + new for old, new in maps],
        **stats,
    }

    if arguments.overlay is not None:
        actual_overlay_hash = _sha256_file(arguments.overlay)
        if actual_overlay_hash != arguments.overlay_sha256:
            raise ValueError("source SHA-256 does not match --overlay-sha256")
        overlay_lines, _ = _read_source(
            arguments.overlay, MAX_OVERLAY_BYTES, "combined overlay")
        overlay_words, overlay_bigrams, overlay_stats = parse_combined(overlay_lines)
        report["overlay_sha256"] = actual_overlay_hash
        report.update(overlay_stats)
    else:
        overlay_words, overlay_bigrams = {}, {}

    if maps:
        words, bigrams, replacements = apply_word_maps(words, bigrams, maps)
        report["mapped_words"] = replacements
        overlay_words, overlay_bigrams, _ = apply_word_maps(
            overlay_words, overlay_bigrams, maps)

    words, bigrams, overlay_report = merge_overlay(
        words, bigrams, overlay_words, overlay_bigrams)
    report.update(overlay_report)
    report["accepted_words_final"] = len(words)

    bigrams, capped, below = select_ngrams(
        words, bigrams, arguments.minimum_count, arguments.top_targets)
    report["capped_target_bigrams"] = capped
    report["below_minimum_count_bigrams"] = below
    report["accepted_bigrams"] = len(bigrams)

    if not bigrams and arguments.minimum_count > 1:
        raise ValueError("no retained n-grams; lower --minimum-count")

    connection = sqlite3.connect(":memory:")
    try:
        connection.executescript(
            "CREATE TABLE selected "
            "(context TEXT NOT NULL, target TEXT NOT NULL, count INTEGER NOT NULL);"
            "CREATE TABLE word_counts (word TEXT PRIMARY KEY, count INTEGER NOT NULL);"
        )
        connection.executemany(
            "INSERT INTO word_counts(word, count) VALUES (?, ?)", sorted(words.items())
        )
        connection.executemany(
            "INSERT INTO selected(context, target, count) VALUES (?, ?, ?)",
            [(context, target, count) for (context, target), count in sorted(bigrams.items())],
        )
        report_contents = (
            json.dumps(report, sort_keys=True, separators=(",", ":")) + "\n"
        ).encode("utf-8")
        import_google_books_ngrams._publish_generation(
            connection, arguments.words_output, arguments.ngrams_output,
            (arguments.report_output.name, report_contents), arguments.report_output,
        )
    finally:
        connection.close()


if __name__ == "__main__":
    main()
```

Notes for the implementer:
- Raw frequencies go into SQLite; `_publish_generation` applies its single linear `score(count, maximum)` stretch per table — order-preserving and deterministic.
- Word counts are clamped implicitly: upstream `f >= 1` survives `score()` ≥ 1.

- [ ] **Step 4: Run tests, verify green**

Run: `python3 -m unittest tools.prediction.test_import_aosp_combined -v`
Expected: all pass.

- [ ] **Step 5: Run the neighbouring suites for regressions**

Run: `python3 -m unittest tools.prediction.test_import_archimob tools.prediction.test_import_ecdc_tm tools.prediction.test_import_google_books_ngrams`
Expected: all pass (shared publishing helpers untouched).

- [ ] **Step 6: Commit**

```bash
git add tools/prediction/import_aosp_combined.py tools/prediction/test_import_aosp_combined.py
git commit -m "feat: import AOSP combined wordlists into dictionary generations"
```

---

### Task 5: Source provenance documentation

**Files:**
- Create: `tools/prediction/sources/aosp-dictionaries.md`
- Modify: `tools/prediction/README.md`

- [ ] **Step 1: Write the source document**

Create `tools/prediction/sources/aosp-dictionaries.md` (fill the four hashes from `/tmp/opencode/packs-promotion/hashes.json`):

```markdown
# Helium314 aosp-dictionaries wordlists (EN, DE, DE-CH)

The `en`, `de`, and `de-CH` production packs are built from the Leipzig Corpora
Collection wordlists compiled by https://codeberg.org/Helium314/aosp-dictionaries
(wordlists_experimental), pinned at Codeberg commit `<CODEBERG_COMMIT>`:

- `main_en_US.combined` — SHA-256 `<MAIN_EN_US_SHA256>` — CC BY 4.0
- `main_de.combined` — SHA-256 `<MAIN_DE_SHA256>` — CC BY 4.0

The `de-CH` pack additionally overlays the OpenBoard v1.4.5 Swiss wordlist:

- `de_wordlist.combined.gz` — SHA-256 `<OPENBOARD_SHA256>`
  https://github.com/openboard-team/openboard/raw/v1.4.5/dictionaries/de_wordlist.combined.gz
  Apache-2.0 (OpenBoard); the upstream source note credits added Helvetismen to
  openthesaurus.de "Schweizer Wörter" under CC BY-SA 4.0.

Licenses:

- Leipzig Corpora Collection: CC BY 4.0 — attribution required, share-alike not
  required. Attribution: "Leipzig Corpora Collection (wortschatz.uni-leipzig.de),
  CC BY 4.0"; compilation by Helium314/aosp-dictionaries.
- Combined effect for `de-CH`: CC BY 4.0 + Apache-2.0 + CC BY-SA 4.0 portions;
  documented in `ATTRIBUTION.de-CH.md`.

Transformations applied by `tools/prediction/import_aosp_combined.py`:

- NFC normalization and deterministic sorting happen in `build_language_pack.py`.
- `de-CH`: literal `ß -> ss` replacement on base and overlay (Swiss orthography);
  collisions keep the maximum frequency. The OpenBoard list is merged union-max.
- Bigram retention is bounded by `--minimum-count` and `--top-targets`.

Acquisition locks (registry `acquisition_lock` objects):

- `en` / `de`: url `https://codeberg.org/Helium314/aosp-dictionaries`,
  version `<CODEBERG_COMMIT>`, single shard named like the downloaded file.
- `de-CH`: same url/version, two shards (`main_de.combined`,
  `openboard_de_CH_wordlist.combined.gz`); each provenance corpus names its shard.

Never commit the downloads, generated TSVs, combined sources, or reports.
Promotion follows `../../assets/latinime/packs/README.md` with `SOURCE_DATE_EPOCH=0`,
the pinned AOSP checkout, and JDK `17.0.19`, using a staging registry copy; record
the `.current.json` and import-report SHA-256 values in each attestation.
```

Substitute the real values from `hashes.json` for every `<…SHA256>` / `<CODEBERG_COMMIT>` marker.

- [ ] **Step 2: Update the pipeline README**

In `tools/prediction/README.md`, extend the paragraph about checked-in packs with one sentence after the sentence ending "outside Git.":

```markdown
The `en`, `de`, and `de-CH` packs are rebuilt the same way from
`sources/aosp-dictionaries.md` using `import_aosp_combined.py` (Leipzig-derived
CC BY 4.0 wordlists; `de-CH` adds the OpenBoard Swiss overlay with `ß->ss`
mapping and a two-shard acquisition lock).
```

- [ ] **Step 3: Commit**

```bash
git add tools/prediction/sources/aosp-dictionaries.md tools/prediction/README.md
git commit -m "docs: pin aosp-dictionary wordlist sources for en/de/de-CH"
```

---

### Task 6: Import three TSV generations

**Files (all scratch, uncommitted):**
- `/tmp/opencode/packs-promotion/gen/{en,de,de-CH}.{words,ngrams}.tsv`
- `/tmp/opencode/packs-promotion/gen/{en,de,de-CH}.import-report.json`
- `/tmp/opencode/packs-promotion/gen/.{en,de,de-CH}.*.current.json` pointers

- [ ] **Step 1: Import English**

```bash
cd /home/pascal/Code/Unexpected-Keyboard-upstream-port/.worktrees/next-word-prediction
python3 -m tools.prediction.import_aosp_combined \
  --input /tmp/opencode/packs-promotion/downloads/main_en_US.combined \
  --input-sha256 "$(python3 -c "import json;print(json.load(open('/tmp/opencode/packs-promotion/hashes.json'))['main_en_US.combined'])")" \
  --words-output /tmp/opencode/packs-promotion/gen/en.words.tsv \
  --ngrams-output /tmp/opencode/packs-promotion/gen/en.ngrams.tsv \
  --report-output /tmp/opencode/packs-promotion/gen/en.import-report.json \
  --minimum-count 1 --top-targets 16
cat /tmp/opencode/packs-promotion/gen/en.import-report.json
```

Expected: report shows ~280k accepted words and a large `accepted_bigrams` count.

- [ ] **Step 2: Import German**

Same command with `main_de.combined`, `de.words.tsv`, `de.ngrams.tsv`, `de.import-report.json`, and its hash key.

- [ ] **Step 3: Import German (Switzerland), hybrid**

```bash
python3 -m tools.prediction.import_aosp_combined \
  --input /tmp/opencode/packs-promotion/downloads/main_de.combined \
  --input-sha256 "$(python3 -c "import json;print(json.load(open('/tmp/opencode/packs-promotion/hashes.json'))['main_de.combined'])")" \
  --overlay /tmp/opencode/packs-promotion/downloads/openboard_de_CH_wordlist.combined.gz \
  --overlay-sha256 "$(python3 -c "import json;print(json.load(open('/tmp/opencode/packs-promotion/hashes.json'))['openboard_de_CH_wordlist.combined.gz'])")" \
  --map "ß:ss" \
  --words-output /tmp/opencode/packs-promotion/gen/de-CH.words.tsv \
  --ngrams-output /tmp/opencode/packs-promotion/gen/de-CH.ngrams.tsv \
  --report-output /tmp/opencode/packs-promotion/gen/de-CH.import-report.json \
  --minimum-count 1 --top-targets 16
grep -c "	" /tmp/opencode/packs-promotion/gen/de-CH.words.tsv
grep -c "ss" /dev/null; grep -m3 "^Straße" /tmp/opencode/packs-promotion/gen/de-CH.words.tsv || echo "no ß words remain"
```

Expected: report shows `overlay_added_words > 0` (Velotismus), zero remaining `ß` words, and `mapped_words > 0`.

- [ ] **Step 4: Record generated-input hashes**

```bash
python3 - <<'EOF'
import hashlib, json, pathlib
gen = pathlib.Path("/tmp/opencode/packs-promotion/gen")
hashes = {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
          for p in sorted(gen.glob("*.*.tsv"))}
pathlib.Path("/tmp/opencode/packs-promotion/generated-hashes.json").write_text(
    json.dumps(hashes, indent=2, sort_keys=True) + "\n")
print(json.dumps(hashes, indent=2))
EOF
```

These six hashes go into the attestations in Task 8.

---

### Task 7: Build the three packs against a staging registry

**Files (scratch unless noted):**
- `/tmp/opencode/packs-promotion/staging/` — staging registry, placeholders, outputs
- Later copied into `assets/latinime/packs/`

Background: `build_language_pack.py main()` refuses to build for a registry whose ready entry does not already have consistent dict/manifest/attestation artifacts, so first-ever builds stage placeholder artifacts that are internally consistent. After building, only verified outputs move into Git.

- [ ] **Step 1: Create staging placeholders and registry**

```bash
python3 - <<'EOF'
import hashlib, json, pathlib

root = pathlib.Path("/tmp/opencode/packs-promotion")
staging = root / "staging"
staging.mkdir(exist_ok=True)
hashes = json.loads((root / "hashes.json").read_text())
EMPTY = hashlib.sha256(b"").hexdigest()

codeberg = "https://codeberg.org/Helium314/aosp-dictionaries"
commit = hashes["codeberg_commit"]

def corpus(key, name, shard=None):
    source = {
        "type": "external_corpus",
        "license": "CC BY 4.0" if key.startswith(("main_",)) else "Apache-2.0",
        "source_sha256": hashes[key],
        "url": f"{codeberg}/raw/branch/main/wordlists_experimental/{name}"
               if key.startswith("main_") else
               "https://github.com/openboard-team/openboard/raw/v1.4.5/dictionaries/de_wordlist.combined.gz",
        "version": commit if key.startswith("main_") else hashes["openboard_version"],
    }
    if shard:
        source["shard"] = shard
    return source

packs = []
specs = [
    ("en", [("main_en_US.combined", "main_en_US.combined", None)]),
    ("de", [("main_de.combined", "main_de.combined", None)]),
    ("de-CH", [("main_de.combined", "main_de.combined", "main_de.combined"),
               ("openboard_de_CH_wordlist.combined.gz",
                "openboard_de_CH_wordlist.combined.gz",
                "openboard_de_CH_wordlist.combined.gz")]),
]
licenses = {
    "en": "CC BY 4.0",
    "de": "CC BY 4.0",
    "de-CH": "CC BY 4.0, Apache-2.0, and CC BY-SA 4.0 (openthesaurus Helvetismen)",
}
for locale, sources in specs:
    shards = [{"name": s[0], "sha256": hashes[s[0]]} for s in sources]
    lock = {"state": "locked", "url": codeberg, "version": commit, "shards": shards}
    canonical = json.dumps(lock, sort_keys=True, separators=(",", ":"))
    import hashlib as _h
    lock_hash = _h.sha256(canonical.encode()).hexdigest()
    provenance = {}
    for i, (key, name, shard) in enumerate(sources):
        provenance[f"source_{i}"] = corpus(key, name, shard)
        if shard is None:
            # Single-shard packs carry the canonical lock hash, not the raw file hash.
            provenance[f"source_{i}"]["source_sha256"] = lock_hash
    (staging / f"{locale}.provenance.json").write_text(
        json.dumps(provenance, indent=2, sort_keys=True) + "\n")
    # Placeholder artifacts that satisfy load_language_packs before the first build.
    (staging / f"{locale}.dict").write_bytes(b"")
    manifest = {"locale": locale, "output_sha256": EMPTY, "sources": {}}
    (staging / f"{locale}.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    attestation = {
        "acquisition_lock_sha256": lock_hash,
        "combined_source_sha256": EMPTY,
        "compiler": {}, "final_assets": {"dictionary_sha256": EMPTY, "manifest_sha256": EMPTY},
        "format_version": 1, "generated_inputs": {}, "importer_reports":
        {"generation_manifest_sha256": EMPTY, "generation_report_sha256": EMPTY},
        "locale": locale,
        "source_provenance": {},
    }
    (staging / f"{locale}.attestation.json").write_text(
        json.dumps(attestation, indent=2, sort_keys=True) + "\n")
    packs.append({
        "asset_license": licenses[locale],
        "attribution": f"ATTRIBUTION.{locale}.md",
        "attestation": f"{locale}.attestation.json",
        "attestation_sha256": _h.sha256(
            (staging / f"{locale}.attestation.json").read_bytes()).hexdigest(),
        "acquisition_lock": lock,
        "development_supported": False,
        "dictionary": f"{locale}.dict",
        "locale": locale,
        "manifest": f"{locale}.json",
        "ngram_tsv": f"../gen/{locale}.ngrams.tsv",
        "output_sha256": EMPTY,
        "provenance": f"{locale}.provenance.json",
        "source_sha256": lock_hash,
        "state": "ready",
        "word_frequency_tsv": f"../gen/{locale}.words.tsv",
    })

registry = json.loads(pathlib.Path(
    "assets/latinime/packs/language_packs.json").read_text())
by_locale = {p["locale"]: p for p in registry["packs"]}
new_packs = [by_locale.get(p["locale"], p) for p in packs]
kept = [p for p in registry["packs"] if p["locale"] not in {"en", "de", "de-CH"}]
registry["packs"] = sorted(new_packs + kept, key=lambda p: p["locale"])
(staging / "language_packs.json").write_text(json.dumps(registry, indent=2, sort_keys=True) + "\n")

for locale in ("en", "de", "de-CH"):
    (staging / f"ATTRIBUTION.{locale}.md").write_text("placeholder\n")
print("staging registry written")
EOF
```

Important: `load_language_packs` requires TSV/provenance paths to stay under the registry directory, hence the `../gen/…` relative form resolving inside `/tmp/opencode/packs-promotion/`.

- [ ] **Step 2: Build English**

```bash
export PATH=/usr/lib/jvm/java-17-openjdk-amd64/bin:$PATH
cd /home/pascal/Code/Unexpected-Keyboard-upstream-port/.worktrees/next-word-prediction
SOURCE_DATE_EPOCH=0 python3 -m tools.prediction.build_language_pack \
  --source /tmp/opencode/LatinIME \
  --registry /tmp/opencode/packs-promotion/staging/language_packs.json \
  --locale en \
  --word-frequency-tsv /tmp/opencode/packs-promotion/gen/en.words.tsv \
  --ngram-tsv /tmp/opencode/packs-promotion/gen/en.ngrams.tsv \
  --provenance /tmp/opencode/packs-promotion/staging/en.provenance.json \
  --output /tmp/opencode/packs-promotion/staging/en.dict \
  --manifest /tmp/opencode/packs-promotion/staging/en.json
```

Expected: exits 0; `staging/en.dict` begins with magic `9bc13afe` (`xxd -l 6 staging/en.dict`); `staging/en.combined` appears alongside.

Repeat verbatim for `--locale de` and `--locale de-CH` (same paths, locale swapped). Expected total wall time: a few minutes each (dicttool compiles fresh per invocation).

- [ ] **Step 3: Review build results**

```bash
ls -la /tmp/opencode/packs-promotion/staging/*.dict
python3 -c "
import json
for locale in ('en','de','de-CH'):
    m=json.load(open(f'/tmp/opencode/packs-promotion/staging/{locale}.json'))
    print(locale, m['output_sha256'], m['combined_source_sha256'][:12], m['timestamp'])
"
```

Expected: three dicts in the low-single-digit MB range, timestamps `1970-01-01T00:00:00Z`.

---

### Task 8: Attest, register, attribute

**Files:**
- Replace: `assets/latinime/packs/{en,de,de-CH}.dict`, `{en,de,de-CH}.json`, `{en,de,de-CH}.attestation.json`
- Replace: `assets/latinime/packs/{en,de}.attestation.json` contents, create `de-CH.attestation.json`
- Modify: `assets/latinime/packs/language_packs.json`
- Rewrite: `assets/latinime/packs/ATTRIBUTION.en.md`, `ATTRIBUTION.de.md`
- Create: `assets/latinime/packs/ATTRIBUTION.de-CH.md`

- [ ] **Step 1: Assemble real attestations**

```bash
cd /home/pascal/Code/Unexpected-Keyboard-upstream-port/.worktrees/next-word-prediction
export PATH=/usr/lib/jvm/java-17-openjdk-amd64/bin:$PATH
python3 - <<'EOF'
import hashlib, json, pathlib, sys
sys.path.insert(0, ".")
from tools.prediction.build_language_pack import acquisition_lock_sha256

staging = pathlib.Path("/tmp/opencode/packs-promotion/staging")
gen = pathlib.Path("/tmp/opencode/packs-promotion/gen")
def h(p): return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()

registry = json.loads((staging / "language_packs.json").read_text())
for locale in ("en", "de", "de-CH"):
    pack = next(p for p in registry["packs"] if p["locale"] == locale)
    manifest = json.loads((staging / f"{locale}.json").read_text())
    pointer = gen / f".{locale}.words.tsv.{locale}.ngrams.tsv.current.json"
    report_path = gen / f"{locale}.import-report.json"
    attestation = {
        "acquisition_lock_sha256": acquisition_lock_sha256(pack["acquisition_lock"]),
        "combined_source_sha256": manifest["combined_source_sha256"],
        "compiler": manifest["compiler"],
        "final_assets": {
            "dictionary_sha256": h(staging / f"{locale}.dict"),
            "manifest_sha256": h(staging / f"{locale}.json"),
        },
        "format_version": 1,
        "generated_inputs": {
            "words": {"sha256": manifest["sources"]["word_frequency_tsv"]["sha256"]},
            "ngrams": {"sha256": manifest["sources"]["ngram_tsv"]["sha256"]},
        },
        "importer_reports": {
            "generation_manifest_sha256": h(pointer),
            "generation_report_sha256": h(report_path),
        },
        "locale": locale,
        "source_provenance": manifest["sources"],
    }
    (staging / f"{locale}.attestation.json").write_text(
        json.dumps(attestation, indent=2, sort_keys=True) + "\n")
    pack["attestation_sha256"] = h(staging / f"{locale}.attestation.json")
    pack["output_sha256"] = manifest["output_sha256"]

(staging / "language_packs.json").write_text(
    json.dumps(registry, indent=2, sort_keys=True) + "\n")
print("attestations assembled")
EOF
```

- [ ] **Step 2: Validate the staging chain before copying**

```bash
python3 -c "
import sys; sys.path.insert(0, '.')
from tools.prediction.build_language_pack import load_language_packs
packs = load_language_packs('/tmp/opencode/packs-promotion/staging/language_packs.json')
print(sorted(p['locale'] for p in packs))
"
```

Expected: `['de', 'de-CH', 'en', 'gsw']` with no exception. If this fails, fix the staging artifacts before touching Git.

- [ ] **Step 3: Promote verified artifacts into the repository**

```bash
cp /tmp/opencode/packs-promotion/staging/{en,de,de-CH}.dict assets/latinime/packs/
cp /tmp/opencode/packs-promotion/staging/{en,de,de-CH}.json assets/latinime/packs/
cp /tmp/opencode/packs-promotion/staging/{en,de,de-CH}.attestation.json assets/latinime/packs/
cp /tmp/opencode/packs-promotion/staging/language_packs.json assets/latinime/packs/language_packs.json
```

- [ ] **Step 4: Rewrite attribution documents**

Replace the contents of `assets/latinime/packs/ATTRIBUTION.en.md`:

```markdown
# English production pack attribution

Dictionary data: Leipzig Corpora Collection word lists for US English, compiled
by Helium314/aosp-dictionaries (wordlists_experimental/main_en_US.combined).

Copyright © Leipzig Corpora Collection contributors. The source word lists are
licensed under Creative Commons Attribution 4.0 International
(https://creativecommons.org/licenses/by/4.0/). Changes: converted to AOSP
LatinIME format 202, filtered and re-scored for keyboard prediction.

This pack replaces the previous ECDC Translation Memory derivation.
```

Replace the contents of `assets/latinime/packs/ATTRIBUTION.de.md`:

```markdown
# German production pack attribution

Dictionary data: Leipzig Corpora Collection word lists for German, compiled by
Helium314/aosp-dictionaries (wordlists_experimental/main_de.combined).

Copyright © Leipzig Corpora Collection contributors. The source word lists are
licensed under Creative Commons Attribution 4.0 International
(https://creativecommons.org/licenses/by/4.0/). Changes: converted to AOSP
LatinIME format 202, filtered and re-scored for keyboard prediction.

This pack replaces the previous ECDC Translation Memory derivation.
```

Create `assets/latinime/packs/ATTRIBUTION.de-CH.md`:

```markdown
# German (Switzerland) production pack attribution

Base dictionary data: Leipzig Corpora Collection word lists for German,
compiled by Helium314/aosp-dictionaries
(wordlists_experimental/main_de.combined). Copyright © Leipzig Corpora
Collection contributors, licensed under Creative Commons Attribution 4.0
International (https://creativecommons.org/licenses/by/4.0/).

Swiss overlay: OpenBoard v1.4.5 `de_wordlist.combined.gz`
(https://github.com/openboard-team/openboard/tree/v1.4.5), licensed under
Apache-2.0; the added Helvetismen originate from openthesaurus.de "Schweizer
Wörter" under CC BY-SA 4.0
(https://creativecommons.org/licenses/by-sa/4.0/).

Changes: Swiss orthography (`ß` replaced by `ss`) applied to base and overlay,
union-max merge, conversion to AOSP LatinIME format 202. This pack covers Swiss
Standard German (de-CH); it is distinct from the dialect (gsw) pack and never
substitutes for it. This pack replaces the previous ECDC-derived de-CH build.
```

- [ ] **Step 5: Verify the committed chain**

```bash
python3 -m tools.prediction.verify_production_language_packs
```

Expected: silent exit 0.

- [ ] **Step 6: Run the packaging suite**

```bash
python3 -m unittest tools.prediction.test_release_packaging tools.prediction.test_build_language_pack -v
```

Expected: all pass. If a test asserts the retired ECDC license strings for `en`/`de`/`de-CH`, update that assertion to the new `asset_license` values shown in the registry — never weaken validators that guard `gsw`.

- [ ] **Step 7: Commit the promotion**

```bash
git add assets/latinime/packs
git commit -m "feat: promote Leipzig-based en/de/de-CH prediction packs"
```

---

### Task 9: End-to-end verification

- [ ] **Step 1: Full Python suite**

```bash
python3 -m unittest discover -s tools/prediction -p 'test_*.py' -t .
```

Expected: all tests pass.

- [ ] **Step 2: Development-pack copy smoke test**

```bash
rm -rf /tmp/opencode/packaging-smoke
python3 -m tools.prediction.copy_production_language_packs \
  --registry assets/latinime/packs/language_packs.json \
  --output /tmp/opencode/packaging-smoke
diff <(sha256sum assets/latinime/packs/*.dict | awk '{print $1}') \
     <(sha256sum /tmp/opencode/packaging-smoke/*.dict | awk '{print $1}')
```

Expected: no diff output.

- [ ] **Step 3: Gradle build**

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug
```

Expected: successful APK build containing the enlarged packs.

- [ ] **Step 4: Runtime spot-check (device or emulator, optional but recommended)**

Install the debug APK, switch the keyboard language to `de-CH`, type `Velo` then a space and confirm the next-word suggestions react sensibly; confirm `ß` never appears in `de-CH` completions and that `gsw` suggestions remain dialect words.

- [ ] **Step 5: Final commit of any remaining changes**

```bash
git status --short   # expect clean or docs-only leftovers
```

Commit leftover documentation adjustments:

```bash
git add -A && git commit -m "docs: note rebuilt en/de/de-CH packs in prediction README"
```

---

## Self-review checklist (completed during planning)

- Spec coverage: en ✓ (Task 1/6/7/8), de ✓ (same), de-CH hybrid ✓ (Tasks 3-4 mapping/overlay, Tasks 6-8 build/promote), provenance honesty ✓ (Task 2 + Task 5 docs), chain integrity ✓ (Task 8 Step 5).
- Placeholders: all hashes/commands are concrete procedures; the only runtime-known values (download hashes, Codeberg tip) are captured by scripts, not left to judgment.
- Type consistency: `select_ngrams` returns `(selected, capped, below)` everywhere; `merge_overlay` report keys match CLI test assertions; `validate_provenance(acquisition_lock=...)` used at both call sites.
