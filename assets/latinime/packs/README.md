# LatinIME Language Packs

`packs` contains ready release artifacts and `source_pending` declarations.
`load_language_packs` validates both states, then returns only ready dictionaries
whose manifest and output hash verify.

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
