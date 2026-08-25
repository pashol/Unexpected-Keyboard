# Language-pack builder

`language_packs.json` is the authoritative registry for fixture sources, manifests,
output hashes, and development-pack support. `build_language_pack.py` turns a
registered locale-specific word-frequency TSV and n-gram TSV into an AOSP LatinIME
format-202 dictionary using a caller-provided, clean checkout at the pinned AOSP
revision. It does not download sources or data.

```sh
SOURCE_DATE_EPOCH=0 python3 -m tools.prediction.build_language_pack \
  --source /path/to/LatinIME \
  --registry test/fixtures/latinime/language_packs.json \
  --locale gsw \
  --word-frequency-tsv test/fixtures/latinime/sources/gsw.words.tsv \
  --ngram-tsv test/fixtures/latinime/sources/gsw.ngrams.tsv \
  --provenance test/fixtures/latinime/sources/gsw.provenance.json \
  --output test/fixtures/latinime/minimal_gsw.dict \
  --manifest test/fixtures/latinime/minimal_gsw.json
```

The manifest records source licenses/provenance and hashes, output hash, format,
locale, pinned AOSP compiler revision and source hash, plus the timestamp derived
from `SOURCE_DATE_EPOCH`. Each provenance source hash must match a declared source
TSV. The compiler requires exactly JDK `17.0.19`: `java` must be the sibling runtime
of the resolved `javac`, and both executable versions are verified and recorded in
the fixture hash. Input rows and rendered combined source are Unicode NFC normalized
and sorted deterministically. The `gsw` normalizer deliberately
preserves dialect variants such as `nöd`, `nid`, and `ned`, umlauts, apostrophes, and
casing.

The checked-in English and Swiss German packs under `test/fixtures` are tiny test
fixtures. `assets/latinime/packs/gsw.dict` is a reviewed production artifact;
its archive, import report, and TSV generation stay outside Git. Rebuild it by
following `sources/archimob.md`, using the locked outer archive SHA-256, the
immutable import report receipt, `--minimum-count 2 --top-targets 8`, Java
17.0.19, `SOURCE_DATE_EPOCH=0`, and the pinned AOSP revision. Stage temporary
TSV pointers and provenance beside a temporary registry copy, then copy only
the verified `.dict` and `.json` into the pack directory.

The `en`, `de`, and `de-CH` packs are rebuilt the same way from `sources/aosp-dictionaries.md` using `import_aosp_combined.py` (Leipzig-derived CC BY 4.0 wordlists; `de-CH` adds the OpenBoard Swiss overlay with `ß->ss` mapping and a two-shard acquisition lock).

The committed `assets/latinime/packs/gsw.attestation.json` is the promotion
receipt. Its registry-bound hash ties the acquired archive lock, importer
generation/report hashes, generated TSV hashes, combined input, compiler
identity, source provenance, and final assets together. CI must not attempt to
re-acquire or rebuild the authenticated corpus. It validates that cryptographic
chain and the generated APK assets instead; only a promotion workstation with
the authenticated external generation performs a rebuild comparison.

Run the development fixture copier from the repository root as a package module:

```sh
python3 -m tools.prediction.copy_development_language_packs \
  --registry test/fixtures/latinime/language_packs.json \
  --output build/generated-assets/latinime/packs
```
