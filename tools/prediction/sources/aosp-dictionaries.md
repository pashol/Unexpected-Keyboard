# Helium314 aosp-dictionaries wordlists (EN, DE, DE-CH)

The `en`, `de`, and `de-CH` production packs are built from the Leipzig Corpora
Collection wordlists compiled by https://codeberg.org/Helium314/aosp-dictionaries
(wordlists_experimental), pinned at Codeberg commit
`69afafc3887d189515fa0be8b4585b91df80b92d`:

- `main_en_US.combined` — SHA-256
  `466c2eb4737fa1a9453e5f4a33337db8dd703f75012b70c2534490fd189c9487`
  https://codeberg.org/Helium314/aosp-dictionaries/raw/commit/69afafc3887d189515fa0be8b4585b91df80b92d/wordlists_experimental/main_en_US.combined
  CC BY 4.0
- `main_de.combined` — SHA-256
  `873f0c90e104c574609d8da6a938e4a7c951d53e20cddc1b66301d4d37752b6d`
  https://codeberg.org/Helium314/aosp-dictionaries/raw/commit/69afafc3887d189515fa0be8b4585b91df80b92d/wordlists_experimental/main_de.combined
  CC BY 4.0

The `de-CH` pack additionally overlays the OpenBoard v1.4.5 Swiss wordlist:

- `de_wordlist.combined.gz` — SHA-256
  `58841f047644ef8472378271de08d30ad80fa08f1902991159db7dc2018a0398`
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
  version `69afafc3887d189515fa0be8b4585b91df80b92d`, single shard named like the
  downloaded file.
- `de-CH`: same url/version, two shards (`main_de.combined`,
  `openboard_de_CH_wordlist.combined.gz`); each provenance corpus names its shard.

Never commit the downloads, generated TSVs, combined sources, or reports.
Rebuild and promote per locale:

1. Import with `import_aosp_combined.py` into a scratch generation directory
   (`--minimum-count 1 --top-targets 16`; `de-CH` adds `--map "ß:ss"` plus the
   OpenBoard overlay and its SHA-256).
2. Stage a temporary registry copy whose ready entries point at those TSVs,
   with placeholder dict/manifest/attestation artifacts that satisfy
   `load_language_packs` (empty dictionary files, matching placeholder hashes),
   then build with `SOURCE_DATE_EPOCH=0`, the pinned AOSP checkout, and JDK
   `17.0.19`. The builder resolves TSV inputs through the `.current.json`
   pointer manifests.
3. After each build, regenerate that locale's attestation from the real
   manifest (`combined_source_sha256`, `compiler`, `generated_inputs`,
   `source_provenance`), the real `final_assets` hashes, and the SHA-256 of
   the pointer manifest and import report; update the registry's
   `output_sha256` and `attestation_sha256`.
4. Copy only the verified `.dict`, `.json`, and `.attestation.json` into
   `assets/latinime/packs/`, rewrite the registry's scratch TSV/provenance
   paths to their `sources/<locale>…` conventions, and confirm
   `verify_production_language_packs` passes before committing.
