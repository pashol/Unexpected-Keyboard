# ArchiMob transcript source

The Swiss German (`gsw`) importer accepts only an explicitly acquired UTF-8
**text transcript export** from ArchiMob. It never downloads a corpus. Its
source scope excludes ArchiMob audio and annotations with separate terms. The
concrete source record is SwissuBase study `20154`, catalogue record `19410`:
<https://www.swissubase.ch/en/catalogue/studies/20154/19410/overview>. The
established public metadata identifies Release 2 (2019), dataset `2269`,
version `1.0` (legacy `1.0.0`), version DOI
`10.48656/496p-3w34`, and collection `30843` (Release 2 XML archive). Use
only a transcript export whose text rights are explicitly stated as CC
BY-NC-SA 4.0.

The verified released outer archive is `swissubase_2269_1_0.zip` (version
`1.0`) with SHA-256
`1e417aabb2b7edda51b00c8b283710306e03a6ceceb62059446bd4c2d929d46a`.
It contains the Release 2 member `Archimob_Release_2.zip`; the importer accepts
only that member and only its TEI XML transcripts.

ArchiMob transcript-derived TSVs and any resulting `gsw` binary are licensed
under [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/).
Every generated asset must carry this attribution statement:

> Source: ArchiMob text transcripts (SwissuBase study 20154, catalogue record
> 19410), https://www.swissubase.ch/en/catalogue/studies/20154/19410/overview,
> CC BY-NC-SA 4.0, https://creativecommons.org/licenses/by-nc-sa/4.0/.
> Modified by Unexpected Keyboard: filtered and transformed into word and
> bigram frequency data.

Release notices must preserve the ShareAlike requirement and remain
non-commercial. A commercial or otherwise incompatible `gsw` distribution is
not permitted.

## Acquisition lock

There is no anonymous transcript/export URL. Before a ready pack can build,
the registry and provenance record must contain the authenticated downloaded
transcript/export version, export date, exporter or source-file identity, the
verified outer SHA-256 above, and the catalogue URL. The source hash is an
acquisition lock: pass that exact value as `--source-sha256`, and the importer
rejects a different input before it decodes or processes transcript text.

The public DIP MD5 `ca7356cb1e10d128c41ef1f8f930b54b` is not a
transcript/export source lock and must not be used as `--source-sha256` or
recorded as a source SHA-256. This document intentionally does not invent an
authenticated download date or exporter/source-file identity. No `gsw` pack
can be promoted to `ready` or built as a ready pack until the actual
authenticated downloaded transcript/export SHA-256 and release fields are
locked in its registry entry and provenance record.

```bash
python3 -m tools.prediction.import_archimob \
  --input /approved/swissubase_2269_1_0.zip \
  --source-sha256 1e417aabb2b7edda51b00c8b283710306e03a6ceceb62059446bd4c2d929d46a \
  --words-output /review/gsw.words.tsv \
  --ngrams-output /review/gsw.ngrams.tsv \
  --report-output /review/gsw.import-report.json \
  --minimum-count 2 \
  --top-targets 8
```

The immutable report in the active generation records accepted and rejected
line counts plus the verified source hash. The requested `--report-output`
path is atomically replaced with a format-1 JSON receipt, not a mutable copy
of that report. Its `active_generation.current_manifest_sha256` must equal the
SHA-256 of the active TSV generation pointer, and its
`active_generation.report_sha256` must equal that pointer's immutable report
hash. Its temporary receipt is staged before the active pointer is replaced,
but the requested path is not made visible until after that replacement
succeeds. If active-pointer replacement fails, both its prior value and the
prior receipt remain visible. If receipt replacement fails after the active
pointer changes, the importer atomically restores the prior receipt (if it was
made visible) and prior active pointer; if either restoration itself fails,
the command fails with an explicit rollback error and neither publication can
be considered current.

Intermediate TSVs, receipts, transcript exports, and all corpus data are build
inputs and must not be committed to Git.
