# LatinIME Language Packs

This registry separates released packs from source-pending pack declarations.
`packs` contains only released dictionaries and manifests. `load_language_packs`
verifies every released dictionary, manifest, and output hash before accepting it.

Production source archives and intermediate TSV files are outside version control.
Until a source pack is processed into a release, its locale, license, and
attribution are listed in `source_pending` without references to nonexistent
artifacts. Dictionary binaries and manifests are checked-in, reviewable release
artifacts when they are available.
