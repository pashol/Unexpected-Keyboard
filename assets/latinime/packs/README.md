# LatinIME Language Packs

`packs` contains ready release artifacts and `source_pending` declarations.
`load_language_packs` validates both states, then returns only ready dictionaries
whose manifest and output hash verify.

Production source archives and intermediate TSV files are outside version control.
Until a source pack is processed into a release, its `source_pending` entry
declares its locale, license, attribution, source identifier, and source
location without references to nonexistent artifacts. Dictionary binaries and
manifests are checked-in, reviewable release artifacts when they are available.
