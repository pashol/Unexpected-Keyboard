# LatinIME Language Packs

`packs` contains ready release artifacts and `source_pending` declarations.
`load_language_packs` validates both states, then returns only ready dictionaries
whose manifest, output hash, acquisition lock, external-corpus provenance, and
hash-bound attestation verify. A ready GSW pack also requires its CC BY-NC-SA
4.0 non-commercial and ShareAlike attribution notice.

Production source archives and intermediate TSV files are outside version control.
Until a source pack is processed into a release, its `source_pending` entry
declares its locale, license, attribution, source identifier, and source
location, plus planned relative dictionary and manifest paths. Those paths
describe future assets; they do not assert that binaries or manifests exist.
Dictionary binaries and manifests are checked-in, reviewable release artifacts
when they are available. Pending entries do not require the planned files or an
output hash until the entry becomes ready.

Source-pending entries name a source URL, known version or revision, planned
acquisition metadata path, and an acquisition lock. An `unlocked` entry has no
recorded `source_sha256` and cannot be built. It must be changed to `locked`
with a valid recorded SHA-256 after source acquisition before it may progress to
a ready pack.

## Rebuilding GSW

The checked-in `gsw.dict` and `gsw.json` were built from
`swissubase_2269_1_0.zip`, whose outer SHA-256 is
`1e417aabb2b7edda51b00c8b283710306e03a6ceceb62059446bd4c2d929d46a`.
The ready registry lock records that archive as the sole shard, its catalogue
URL, and version `1.0`; its aggregate lock hash is the external corpus source
hash in `gsw.json`. Restore the archive outside the repository, verify its
outer hash, then run `tools.prediction.import_archimob` with `--minimum-count
2 --top-targets 8` and retain the immutable active-generation report receipt.
Stage the generated TSV pointers and provenance alongside a temporary copy of
the registry, use Java 17 with `SOURCE_DATE_EPOCH=0` and the pinned AOSP
dicttool revision, and compare the resulting dictionary and manifest hashes
with the checked-in assets. Do not commit the archive, report, combined input,
or TSV files.

`gsw.attestation.json` is the committed receipt for that promotion. Its SHA-256
is bound by the ready registry entry and it records the acquisition lock,
importer generation/report hashes, generated word and n-gram TSV hashes,
combined-source hash, compiler identity, final dictionary/manifest hashes, and
source provenance. CI intentionally does not rebuild authenticated ArchiMob
input: it verifies this receipt, the registry, manifest, attribution, and
dictionary cryptographic chain, then verifies the generated APK assets. A
promotion workstation may additionally compare the receipt with its external
generation, but must not commit the corpus or generated TSVs.
